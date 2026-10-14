#!/usr/bin/env python3
"""Same-account, loopback read-only HTTP measurements. Credentials are supplied via a private header file.
Reports curl TTFB/total separately from Servlet lifetime and phase sums; never records response content.
"""
import argparse
import concurrent.futures
import json
import os
from pathlib import Path
import re
import statistics
import subprocess
import tempfile
import time
from urllib.parse import urlparse


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', default='http://127.0.0.1:12637/arte')
    parser.add_argument('--auth-header-file', required=True)
    parser.add_argument('--tenant-id', required=True)
    parser.add_argument('--workspace-id', required=True)
    parser.add_argument('--conversation-id')
    parser.add_argument('--repetitions', type=int, default=5)
    parser.add_argument('--concurrency', type=int, default=8)
    parser.add_argument('--log-file', required=True)
    parser.add_argument('--output-dir', required=True)
    parser.add_argument('--lock-sampler', action='store_true', help='Wait for AiMysqlLockSampler marker acknowledgments')
    parser.add_argument('--sample-only', action='store_true', help='Bootstrap and one request for each endpoint; no two-group run')
    parser.add_argument('--include-sse', action='store_true', help='Sample existing invocation SSE separately, up to 70 seconds')
    args = parser.parse_args()
    if urlparse(args.base_url).hostname not in {'127.0.0.1', 'localhost', '::1'}:
        parser.error('Only loopback HTTP endpoints are allowed')
    header = Path(args.auth_header_file)
    if not header.is_file() or header.stat().st_mode & 0o077:
        parser.error('Authentication header file must exist and have private permissions (0600)')
    if not 1 <= args.concurrency <= 16 or not 1 <= args.repetitions <= 20:
        parser.error('Concurrency must be 1..16 and repetitions 1..20')
    output = Path(args.output_dir)
    output.mkdir(parents=True, exist_ok=True)
    os.chmod(output, 0o700)
    if list(output.glob('*-start.marker')) or list(output.glob('*-end.marker')):
        parser.error('Use a fresh output directory; stale phase markers would invalidate lock counter boundaries')
    scope = {'tenantId': args.tenant_id, 'workspaceId': args.workspace_id}
    run_id = 'ai-' + time.strftime('%Y%m%d-%H%M%S')
    rows = []

    def request(path, body, measurement_id, parse=False, timeout=40):
        with tempfile.TemporaryDirectory(prefix='arte-http-') as temp:
            folder = Path(temp)
            payload, headers, content = (folder / name for name in ['request.json', 'headers', 'body'])
            payload.write_text(json.dumps(body))
            started = int(time.time() * 1000)
            call = subprocess.run(['curl', '--silent', '--show-error', '--compressed', '--http1.1', '--noproxy', '*',
                '--max-time', str(timeout), '--header', '@' + str(header.resolve()), '--header', 'Content-Type: application/json',
                '--header', 'X-Arte-Measurement-Id: ' + measurement_id, '--data-binary', '@' + str(payload),
                '--dump-header', str(headers), '--output', str(content), '--write-out', '%{json}',
                args.base_url.rstrip('/') + '/ai-new/' + path], capture_output=True, text=True)
            stats = json.loads(call.stdout or '{}')
            response_headers = {}
            if headers.exists():
                for line in headers.read_text(errors='replace').splitlines():
                    if ':' in line:
                        key, value = line.split(':', 1)
                        response_headers[key.lower().strip()] = value.strip()
            sample = {'measurementId': measurement_id, 'path': path, 'clientStartedEpochMs': started,
                'clientFinishedEpochMs': int(time.time() * 1000), 'httpStatus': stats.get('http_code'),
                'curlExit': call.returncode, 'ttfbMs': round(stats.get('time_starttransfer', 0) * 1000, 3),
                'totalMs': round(stats.get('time_total', 0) * 1000, 3),
                'connectMs': round(stats.get('time_connect', 0) * 1000, 3),
                'responseBytes': stats.get('size_download'), 'traceId': response_headers.get('x-arte-trace-id')}
            data = None
            if content.exists() and call.returncode == 0 and path != 'invocation/watchInvocation':
                try:
                    data = json.loads(content.read_text())
                    sample['businessSuccess'] = isinstance(data, dict) and data.get('success') is True
                    sample['businessCode'] = data.get('code') if isinstance(data, dict) else None
                except ValueError:
                    sample['businessSuccess'] = False
            return sample, data if parse else None

    def bootstrap(path, body):
        sample, data = request(path, body, run_id + '-setup-' + path.rsplit('/', 1)[-1], True)
        if sample['httpStatus'] != 200 or not isinstance(data, dict) or data.get('success') is not True:
            raise RuntimeError('Bootstrap failed: ' + path + ', HTTP ' + str(sample['httpStatus']))
        return data

    options = bootstrap('configuration/discoverChatOptions', {'scope': scope})['data']['options']
    budget_ref = next((ref for option in options for ref in option['budgetRefs']), None)
    if not budget_ref:
        raise RuntimeError('Selected workspace has no visible compatible budget')
    page = bootstrap('conversation/listConversations', {'scope': scope, 'page': {'current': 1, 'size': 20}})
    conversation_id = args.conversation_id or next((row['conversationId'] for row in page['records']), None)
    if not conversation_id:
        raise RuntimeError('No existing conversation for the read-only measurement')
    conversation = bootstrap('conversation/getConversation', {'scope': scope, 'conversationId': conversation_id})['data']
    turns_body = {'scope': scope, 'conversationId': conversation_id, 'expectedVersion': conversation['version'], 'page': {'current': 1, 'size': 10}}
    turns = bootstrap('conversation/queryTurnsOfConversation', turns_body)
    if turns['total'] > 10:
        turns_body['page']['current'] = max(1, (turns['total'] + 9) // 10)
        turns = bootstrap('conversation/queryTurnsOfConversation', turns_body)
    ids = list(dict.fromkeys(id for turn in turns['records'] for id in [turn.get('selectedInvocationId'), *turn['invocationIds']] if id))
    snapshots = bootstrap('invocation/getInvocationSnapshots', {'scope': scope, 'invocationIds': ids[-20:]})['data'] if ids else []
    target = next((row for row in reversed(snapshots) if row.get('result') is not None), None)
    endpoints = [
        ('configuration/discoverChatOptions', {'scope': scope}),
        ('budget/getBudget', {'scope': scope, 'budgetRef': budget_ref}),
        ('conversation/getConversation', {'scope': scope, 'conversationId': conversation_id}),
        ('conversation/listConversations', {'scope': scope, 'page': {'current': 1, 'size': 20}}),
        ('conversation/queryTurnsOfConversation', turns_body),
    ]
    if target:
        invocation = {'scope': scope, 'invocationId': target['invocationId']}
        endpoints += [('invocation/getInvocationStatus', invocation), ('invocation/getInvocationResult', invocation),
            ('invocation/getInvocationSnapshots', {'scope': scope, 'invocationIds': ids[-20:]})]
    config = {'runId': run_id, 'baseUrl': args.base_url, 'scope': scope, 'conversationId': conversation_id,
        'budgetRef': budget_ref, 'invocationId': target['invocationId'] if target else None,
        'repetitions': args.repetitions, 'concurrency': args.concurrency, 'readEndpoints': [path for path, _ in endpoints]}
    (output / 'configuration.json').write_text(json.dumps(config, indent=2))
    print(json.dumps({'runId': run_id, 'endpoints': len(endpoints), 'bootstrap': 'successful'}), flush=True)
    phases = [('single', 1)] if args.sample_only else [('single', 1), ('concurrent', args.concurrency)]
    def wait_marker(name):
        deadline = time.monotonic() + 10
        while not (output / name).exists():
            if time.monotonic() >= deadline:
                raise RuntimeError('Lock sampler did not acknowledge ' + name)
            time.sleep(0.025)

    for phase, width in phases:
        started = time.time()
        (output / (phase + '-start.marker')).write_text(str(int(started * 1000)))
        if args.lock_sampler:
            wait_marker(phase + '-lock-start.ready')
        with concurrent.futures.ThreadPoolExecutor(max_workers=width) as pool:
            for index, (path, body) in enumerate(endpoints):
                # A cold request is outside the samples; two groups reuse the same account and endpoint payload.
                request(path, body, run_id + '-warm-' + phase + '-' + str(index))
                for repetition in range(1 if args.sample_only else args.repetitions):
                    futures = [pool.submit(request, path, body, f'{run_id}-{phase}-{index}-{repetition}-{n}') for n in range(width)]
                    for future in futures:
                        row, _ = future.result()
                        row['group'] = phase
                        rows.append(row)
                print(json.dumps({'group': phase, 'endpoint': path, 'completedSamples': len(rows)}), flush=True)
        (output / (phase + '-end.marker')).write_text(str(int(time.time() * 1000)))
        if args.lock_sampler:
            wait_marker(phase + '-lock-end.ready')
    if args.include_sse and target:
        row, _ = request('invocation/watchInvocation', {**invocation, 'afterSequence': 0}, run_id + '-sse', timeout=70)
        row['group'] = 'sse'; rows.append(row)
    (output / 'client-results.json').write_text(json.dumps(rows, indent=2))
    # Async completion can reach the log just after curl finishes.
    time.sleep(0.3)
    reports = {}
    for line in Path(args.log_file).read_text(errors='replace').splitlines():
        if 'AI_HTTP_METRICS ' in line:
            try:
                report = json.loads(line.split('AI_HTTP_METRICS ', 1)[1])
                reports[report['traceId']] = report
            except (ValueError, KeyError):
                pass
    for row in rows:
        row['server'] = reports.get(row['traceId'])
    (output / 'results.json').write_text(json.dumps(rows, indent=2))
    summary = []
    for phase, _ in phases:
        for path, _ in endpoints:
            selected = [row for row in rows if row['group'] == phase and row['path'] == path]
            entry = {'group': phase, 'path': path, 'count': len(selected),
                'successes': sum(row['httpStatus'] == 200 and row['curlExit'] == 0 and row.get('businessSuccess') is True for row in selected),
                'matchedTraces': sum(row['server'] is not None for row in selected)}
            for field in ['ttfbMs', 'totalMs']:
                values = sorted(row[field] for row in selected)
                entry[field] = {'median': statistics.median(values), 'p95': values[max(0, (len(values) * 95 + 99) // 100 - 1)]}
            server = [row['server'] for row in selected if row['server'] is not None]
            for field in ['sqlCount', 'sqlExecuteMs', 'initialAuthorizationMs', 'connectionAcquireMs', 'mvcTotalMs']:
                entry[field] = statistics.median(row[field] for row in server) if server else None
            entry['schedulerQueueSumMs'] = statistics.median(sum(queue['sumMs'] for queue in row['schedulerQueues'].values()) for row in server) if server else None
            summary.append(entry)
    (output / 'summary.json').write_text(json.dumps(summary, indent=2))
    print(json.dumps({'samples': len(rows), 'matchedTraces': sum(row['server'] is not None for row in rows),
        'successes': sum(row['httpStatus'] == 200 and row['curlExit'] == 0 and row.get('businessSuccess') is True for row in rows)}), flush=True)


if __name__ == '__main__':
    main()

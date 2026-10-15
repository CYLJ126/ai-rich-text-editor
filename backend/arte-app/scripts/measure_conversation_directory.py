#!/usr/bin/env python3
"""Read-only loopback HTTP baseline for the conversation directory; never saves response content or credentials."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import json
from pathlib import Path
import math
import time
from urllib.parse import urlparse
from urllib.request import ProxyHandler, Request, build_opener


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', default='http://localhost:8080/arte/ai-new')
    parser.add_argument('--auth-header-file', required=True)
    parser.add_argument('--tenant-id', required=True)
    parser.add_argument('--workspace-id', required=True)
    parser.add_argument('--samples', type=int, default=30)
    parser.add_argument('--concurrency', type=int, default=8)
    args = parser.parse_args()
    url = urlparse(args.base_url)
    if url.scheme != 'http' or url.hostname not in {'localhost', '127.0.0.1', '::1'}:
        parser.error('Only loopback HTTP endpoints are supported')
    if not 1 <= args.samples <= 100 or not 1 <= args.concurrency <= 8:
        parser.error('Samples must be 1..100 and concurrency 1..8')
    secret = Path(args.auth_header_file)
    if not secret.is_file() or secret.stat().st_mode & 0o077:
        parser.error('Authentication header file must have private permissions (0600)')
    key, value = secret.read_text().strip().split(':', 1)
    headers = {key: value.strip(), 'Content-Type': 'application/json', 'Accept-Language': 'zh-CN'}
    scope = {'tenantId': args.tenant_id, 'workspaceId': args.workspace_id}

    def request(endpoint, body):
        started = time.perf_counter()
        call = Request(args.base_url.rstrip('/') + '/conversation/' + endpoint,
                       data=json.dumps(body).encode(), headers=headers, method='POST')
        with build_opener(ProxyHandler({})).open(call, timeout=30) as response:
            raw = response.read()
            status = response.status
        elapsed = (time.perf_counter() - started) * 1000
        payload = json.loads(raw)
        if status != 200 or payload.get('success') is not True or not isinstance(payload.get('data'), dict):
            raise RuntimeError('Directory request failed; no response content was recorded')
        return (elapsed, len(raw)), payload['data']

    def stats(rows):
        durations = sorted(row[0] for row in rows)
        percentile = lambda p: round(durations[max(0, math.ceil(len(durations) * p) - 1)], 3)
        return {'samples': len(rows), 'p50Ms': percentile(.5), 'p95Ms': percentile(.95),
                'maxMs': round(max(durations), 3), 'maxResponseBytes': max(row[1] for row in rows)}

    first = {'scope': scope, 'normalSize': 20}
    _, window = request('queryConversationWindow', first)
    # A size-one read supplies a real server-issued cursor even in a small development dataset.
    _, probe = request('listConversationsCursor', {'scope': scope, 'pinned': False, 'size': 1})
    cursor = probe.get('nextCursor')
    cases = [('firstWindow', 'queryConversationWindow', first)]
    if cursor:
        cases.append(('continuation', 'listConversationsCursor',
                      {'scope': scope, 'pinned': False, 'size': 20, 'cursor': cursor}))
    report = {'readOnly': True, 'includesProxyAndHttp': True, 'modelCalls': 0,
              'firstPinnedRows': len(window['pinned']['records']),
              'firstNormalRows': len(window['normal']['records']),
              'normalTotal': window['normal'].get('total'), 'realContinuationAvailable': bool(cursor), 'cases': {}}
    for name, endpoint, body in cases:
        for _ in range(5):
            request(endpoint, body)
        sequential = [request(endpoint, body)[0] for _ in range(args.samples)]
        with ThreadPoolExecutor(max_workers=args.concurrency) as executor:
            concurrent = list(executor.map(lambda _: request(endpoint, body)[0], range(args.samples)))
        report['cases'][name] = {'sequential': stats(sequential),
                                 'concurrent': {'concurrency': args.concurrency, **stats(concurrent)}}
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()

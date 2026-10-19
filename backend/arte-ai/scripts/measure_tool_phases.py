#!/usr/bin/env python3
"""Read-only TOOL phase baseline from AI_TOOL_STAGE / AI_TOOL_POINT logs; no DB or HTTP access."""
import argparse
import collections
import json
import math
from pathlib import Path


def percentiles(values):
    values = sorted(values)
    if not values:
        return None
    return {name: values[math.ceil(len(values) * fraction) - 1]
            for name, fraction in (("p50", .5), ("p95", .95), ("p99", .99))}


def read_events(paths):
    records, malformed = [], 0
    decoder = json.JSONDecoder()
    for path in paths:
        with Path(path).open(encoding="utf-8", errors="replace") as stream:
            for line in stream:
                for prefix, kind in (("AI_TOOL_STAGE ", "stage"), ("AI_TOOL_POINT ", "point"), ("AI_HTTP_METRICS ", "http")):
                    position = line.find(prefix)
                    if position < 0:
                        continue
                    try:
                        value, _ = decoder.raw_decode(line[position + len(prefix):].lstrip())
                        if not isinstance(value, dict):
                            raise ValueError("Invalid phase record")
                        if kind == "http":
                            if value.get("path") != "/ai-new/tool/invokeTool":
                                break
                        elif not isinstance(value.get("stage"), str):
                            raise ValueError("Invalid stage name")
                        records.append({**value, "kind": kind})
                    except (ValueError, TypeError):
                        malformed += 1
                    break
    return records, malformed


def summarize(records, skip=0, trace_id=None, allow_cross_node=False):
    if trace_id:
        records = [row for row in records if row.get("traceId") == trace_id]
    # Log rotation/copy overlaps must not duplicate samples.
    records = list({json.dumps(row, sort_keys=True): row for row in records}.values())
    trace_to_invocation = {row.get("traceId"): row["invocationId"] for row in records
                           if row.get("stage") == "ADMISSION" and row.get("invocationId") and row.get("traceId")}
    groups = collections.defaultdict(list)
    for row in records:
        invocation_id = trace_to_invocation.get(row.get("traceId")) or row.get("invocationId")
        if invocation_id:
            groups[invocation_id].append(row)
    # Preview-only / initial-auth-only records are not invocation samples.
    groups = {key: rows for key, rows in groups.items()
              if any(row.get("stage") in ("ADMISSION", "WORKER_LOAD", "DISPATCH") for row in rows)}
    ordered = sorted(groups, key=lambda key: min(row.get("startedEpochMs", row.get("epochMs", row.get("mvcStartedEpochMs", 0))) for row in groups[key]))
    selected = set(ordered[skip:])
    rows = [row for key in selected for row in groups[key]]
    phases = collections.defaultdict(list)
    for row in rows:
        if row["kind"] == "stage":
            phases[row["stage"]].append(row)
    metrics = {}
    for name, samples in sorted(phases.items()):
        sql = [row for row in samples if row.get("jdbcObserved") is True]
        metrics[name] = {
            "samples": len(samples),
            "outcomes": dict(collections.Counter(row["outcome"] for row in samples)),
            "elapsedMs": percentiles([row["elapsedMs"] for row in samples]),
            "sqlCount": percentiles([row["sqlCount"] for row in sql]),
            "sqlExecuteMs": percentiles([row["sqlExecuteMs"] for row in sql]),
            "sqlUnobservedSamples": len(samples) - len(sql),
            "partialSqlSamples": sum(row.get("sqlInFlight", 0) > 0 for row in samples),
            "remainingEndMs": percentiles([row["remainingEndMs"] for row in samples if row.get("remainingEndMs") is not None]),
        }
    gaps, cross_node, clock_conflicts, missing = [], 0, 0, 0
    for key in selected:
        commits = [row for row in groups[key] if row.get("stage") == "ADMISSION_COMMITTED"]
        loads = [row for row in groups[key] if row.get("stage") == "WORKER_LOAD"]
        if not commits or not loads:
            missing += 1
            continue
        # Only the first durable acceptance and first Worker read, never an idempotent replay commit.
        commit = min(commits, key=lambda row: row["epochMs"])
        load = min(loads, key=lambda row: row["startedEpochMs"])
        if commit.get("nodeId") != load.get("nodeId"):
            cross_node += 1
            if not allow_cross_node:
                continue
        elapsed = load["startedEpochMs"] - commit["epochMs"]
        if elapsed < 0:
            clock_conflicts += 1
            continue
        gaps.append(elapsed)
    http = [row for row in rows if row["kind"] == "http"]
    unassigned = []
    for request in http:
        top = [row for row in rows if row.get("traceId") == request.get("traceId") and
               row.get("stage") in ("ENTRY_AUTHORIZATION", "ADMISSION") and row.get("jdbcObserved") is True]
        if len(top) == 2 and request.get("sqlInFlight", 0) == 0 and all(row.get("sqlInFlight", 0) == 0 for row in top):
            unassigned.append(request["sqlCount"] - sum(row["sqlCount"] for row in top))
    return {
        "invocationCount": len(selected), "skippedInvocations": min(skip, len(ordered)),
        "entryOnlyFailures": sum(row.get("stage") == "ENTRY_AUTHORIZATION" and row.get("outcome") in ("ERROR", "CANCELLED")
                                 and row.get("invocationId") not in groups for row in records),
        "traceIds": sorted({row["traceId"] for row in rows if row.get("traceId")}),
        "stageMetrics": metrics,
        "http": {"samples": len(http), "statuses": dict(collections.Counter(str(row["status"]) for row in http)),
                 "mvcTotalMs": percentiles([row["mvcTotalMs"] for row in http]),
                 "sqlCount": percentiles([row["sqlCount"] for row in http]),
                 "sqlExecuteMs": percentiles([row["sqlExecuteMs"] for row in http]),
                 "unassignedSqlCount": percentiles(unassigned)},
        "commitToWorkerReadStartMs": {"samples": len(gaps), "latency": percentiles(gaps),
                                      "missingPairs": missing, "crossNodePairs": cross_node,
                                      "negativeClockPairs": clock_conflicts, "allowCrossNodeClock": allow_cross_node},
        "coverage": {name: sum(not any(row.get("stage") == name for row in groups[key]) for key in selected)
                     for name in ("ADMISSION_COMMITTED", "WORKER_LOAD", "ADAPTER", "TERMINAL_COMMIT")},
        "notes": ["Parent stages include child SQL and elapsed time; do not sum parent and child metrics.",
                  "Multiple requests may resolve to one invocation; stage sample counts are not logical invocation counts.",
                  "SUCCESS/EMPTY describe publisher completion, not business outcome; inspect durable terminal state.",
                  "Zero SQL with jdbcObserved=false is unmeasured, not proof of no SQL.",
                  "CANCELLED with SQL in flight is partial; inspect ADMISSION_TRANSACTION and commit/rollback points.",
                  "FOR UPDATE duration is not actual lock wait. No lock-wait value is inferred.",
                  "Commit-to-worker gap includes scheduling/claim before the first invocation read; it is not pure polling delay.",
                  "Cross-node wall-clock gaps are excluded unless explicitly allowed; clocks must be calibrated."],
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("logs", nargs="+")
    parser.add_argument("--trace-id")
    parser.add_argument("--skip-invocations", type=int, default=0)
    parser.add_argument("--allow-cross-node-clock", action="store_true")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    if args.skip_invocations < 0:
        parser.error("--skip-invocations must be non-negative")
    records, malformed = read_events(args.logs)
    report = summarize(records, args.skip_invocations, args.trace_id, args.allow_cross_node_clock)
    report["malformedRecords"] = malformed
    text = json.dumps(report, ensure_ascii=False, indent=2)
    if args.output:
        args.output.write_text(text + "\n", encoding="utf-8")
    print(text)


if __name__ == "__main__":
    main()

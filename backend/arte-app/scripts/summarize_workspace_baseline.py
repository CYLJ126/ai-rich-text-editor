#!/usr/bin/env python3
"""Summarize synthetic workspace directory samples without including identities or SQL parameters."""
import argparse
import json
import math
import statistics
from pathlib import Path


def percentile(values, ratio):
    return sorted(values)[math.ceil(len(values) * ratio) - 1]


def summarize(source):
    report = json.loads(source.read_text())
    scenarios = []
    for scenario in report["scenarios"]:
        samples = scenario["samples"]
        if not samples:
            raise ValueError("Empty scenario")
        summary = {key: value for key, value in scenario.items() if key != "samples"}
        summary["requests"] = len(samples)
        for name in ("totalMs", "queueMs", "sqlMs", "connectionMs", "allocatedBytes"):
            values = [sample[name] for sample in samples]
            summary[name] = {"median": statistics.median(values), "p95": percentile(values, .95)}
        for name in ("sqlCount", "redisReads", "redisWrites", "candidateBatches", "candidateRowsFetched", "returnedRecords"):
            if name not in samples[0]:
                continue
            values = [sample[name] for sample in samples]
            summary[name] = {"min": min(values), "max": max(values)}
        scenarios.append(summary)
    return {**{key: value for key, value in report.items() if key != "scenarios"}, "scenarios": scenarios}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    result = summarize(args.source)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    print("| 候选数 | 种子可见比例 | 并发 | 中位延迟 ms | P95 ms | 排队 P95 ms | SQL/请求 | Redis/请求 | 分配 MiB/请求 |")
    print("|---:|---:|---:|---:|---:|---:|---:|---:|---:|")
    for row in result["scenarios"]:
        print(f"| {row['candidates']:,} | {row['visiblePercentOfSeeded']}% | {row['concurrency']} | "
              f"{row['totalMs']['median']:.1f} | {row['totalMs']['p95']:.1f} | {row['queueMs']['p95']:.1f} | "
              f"{row['sqlCount']['min']}–{row['sqlCount']['max']} | "
              f"{row['redisReads']['min'] + row['redisWrites']['min']}–"
              f"{row['redisReads']['max'] + row['redisWrites']['max']} | "
              f"{row['allocatedBytes']['median'] / 1024 ** 2:.1f} |")


if __name__ == "__main__":
    main()

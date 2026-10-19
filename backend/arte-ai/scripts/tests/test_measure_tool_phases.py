import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("phases", Path(__file__).parents[1] / "measure_tool_phases.py")
phases = importlib.util.module_from_spec(spec)
spec.loader.exec_module(phases)


def stage(name="ADMISSION", invocation="id", started=100, node="same", **kwargs):
    return {"kind": "stage", "stage": name, "invocationId": invocation, "traceId": "trace" if invocation == "id" else "trace-" + invocation, "nodeId": node,
            "startedEpochMs": started, "outcome": "SUCCESS", "elapsedMs": 10, "remainingEndMs": 1000,
            "jdbcObserved": True, "sqlCount": 3, "sqlExecuteMs": 5, "sqlInFlight": 0, **kwargs}


def point(name="ADMISSION_COMMITTED", at=120, node="same"):
    return {"kind": "point", "stage": name, "invocationId": "id", "traceId": "trace", "nodeId": node, "epochMs": at}


class ToolPhaseReportTest(unittest.TestCase):
    def test_nearest_rank_and_missing_values(self):
        self.assertEqual({"p50": 25, "p95": 48, "p99": 50}, phases.percentiles(list(range(1, 51))))
        self.assertIsNone(phases.percentiles([]))

    def test_remaining_time_minima_include_low_and_nonpositive_samples(self):
        rows = [stage(started=100, remainingStartMs=4900, remainingEndMs=4890),
                stage(started=200, remainingStartMs=10, remainingEndMs=0),
                stage(started=300, remainingStartMs=-1, remainingEndMs=-5)]
        metric = phases.summarize(rows)["stageMetrics"]["ADMISSION"]
        self.assertEqual(-1, metric["minimumRemainingStartMs"])
        self.assertEqual(-5, metric["minimumRemainingEndMs"])
        self.assertEqual(1, metric["nonPositiveRemainingStartSamples"])
        self.assertEqual(2, metric["nonPositiveRemainingEndSamples"])
        self.assertEqual(0, metric["missingRemainingStartSamples"])

    def test_missing_remaining_time_is_not_zero_and_filtered_samples_stay_separate(self):
        rows = [stage(remainingEndMs=None), stage(invocation="second", started=300, remainingStartMs=100, remainingEndMs=90)]
        metric = phases.summarize(rows, trace_id="trace")["stageMetrics"]["ADMISSION"]
        self.assertIsNone(metric["minimumRemainingStartMs"])
        self.assertIsNone(metric["minimumRemainingEndMs"])
        self.assertIsNone(metric["remainingStartMs"])
        self.assertEqual(1, metric["missingRemainingStartSamples"])
        self.assertEqual(1, metric["missingRemainingEndSamples"])
        self.assertEqual(0, metric["nonPositiveRemainingEndSamples"])
        self.assertEqual(100, phases.summarize(rows, skip=1)["stageMetrics"]["ADMISSION"]["minimumRemainingStartMs"])

    def test_deduplication_and_parent_sql_are_not_added(self):
        rows = [stage(), stage("COMMIT_WRITES", sqlCount=2), stage()]
        report = phases.summarize(rows)
        self.assertEqual(1, report["invocationCount"])
        self.assertEqual(1, report["stageMetrics"]["ADMISSION"]["samples"])
        self.assertEqual(3, report["stageMetrics"]["ADMISSION"]["sqlCount"]["p50"])

    def test_only_original_commit_is_paired_and_read_duration_is_excluded(self):
        rows = [stage(), point(), point("ADMISSION_REPLAY_COMMITTED", 190), stage("WORKER_LOAD", started=200, elapsedMs=50)]
        gap = phases.summarize(rows)["commitToWorkerReadStartMs"]
        self.assertEqual(80, gap["latency"]["p50"])

    def test_cross_node_and_negative_clock_gaps_are_explicit(self):
        rows = [stage(), point(), stage("WORKER_LOAD", started=200, node="other")]
        self.assertEqual(0, phases.summarize(rows)["commitToWorkerReadStartMs"]["samples"])
        self.assertEqual(1, phases.summarize(rows)["commitToWorkerReadStartMs"]["crossNodePairs"])
        self.assertEqual(1, phases.summarize(rows, allow_cross_node=True)["commitToWorkerReadStartMs"]["samples"])
        rows[-1] = stage("WORKER_LOAD", started=110)
        self.assertEqual(1, phases.summarize(rows)["commitToWorkerReadStartMs"]["negativeClockPairs"])

    def test_unobserved_sql_partial_cancel_preview_and_warmups_are_separate(self):
        rows = [stage(outcome="CANCELLED", sqlInFlight=1), stage("ADAPTER", jdbcObserved=False, sqlCount=0),
                stage("SCHEMA_VALIDATION", invocation="preview"), stage(invocation="second", started=300)]
        report = phases.summarize(rows)
        self.assertEqual(2, report["invocationCount"])
        self.assertEqual(1, report["stageMetrics"]["ADMISSION"]["partialSqlSamples"])
        self.assertIsNone(report["stageMetrics"]["ADAPTER"]["sqlCount"])
        self.assertEqual(1, phases.summarize(rows, skip=1)["invocationCount"])
        self.assertEqual(0, phases.summarize(rows, trace_id="other")["invocationCount"])

    def test_reads_prefixed_json_and_reports_malformed_records(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "app.log"
            path.write_text('prefix AI_TOOL_POINT {"stage":"ADMISSION_COMMITTED"}\nAI_TOOL_STAGE invalid\n', encoding="utf-8")
            rows, malformed = phases.read_events([path])
            self.assertEqual(1, len(rows))
            self.assertEqual(1, malformed)

    def test_http_sql_reconciliation_and_entry_only_failure(self):
        http = {"kind": "http", "traceId": "trace", "status": 202, "mvcTotalMs": 50,
                "mvcStartedEpochMs": 100, "sqlCount": 6, "sqlExecuteMs": 20, "sqlInFlight": 0}
        report = phases.summarize([stage(), stage("ENTRY_AUTHORIZATION", sqlCount=2), http,
                                   stage("ENTRY_AUTHORIZATION", invocation="never-admitted", outcome="ERROR")])
        self.assertEqual(1, report["http"]["samples"])
        self.assertEqual(1, report["http"]["unassignedSqlCount"]["p50"])
        self.assertEqual(1, report["entryOnlyFailures"])

    def test_replay_entry_and_original_id_join_by_current_request_trace(self):
        report = phases.summarize([stage(invocation="original"), stage("ENTRY_AUTHORIZATION", invocation="new-request", traceId="trace-original"),
                                   stage("ADMISSION_TRANSACTION", invocation="original")])
        self.assertEqual(1, report["invocationCount"])
        self.assertEqual(1, report["stageMetrics"]["ENTRY_AUTHORIZATION"]["samples"])


if __name__ == "__main__":
    unittest.main()

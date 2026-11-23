#!/usr/bin/env python3
"""报表口径：缺 tag 不借用全程数字，场景窗口和全程 QPS 分开，按 case 替换。"""
import report
import render


def run(metrics, extra=None, secs=100_000):
    return {"metrics": metrics, "state": {"testRunDurationMs": secs}, "extra": extra or {}}


def test_missing_tag_does_not_borrow_global():
    sample = run({
        "http_req_duration{scenario:reads}": {"med": 1, "p(99)": 2},
        "http_reqs": {"rate": 100, "count": 10000},
        "http_req_failed": {"rate": 0.5},
    })
    assert report.series(sample["metrics"], "http_reqs", "{scenario:reads}") is None
    assert render.series(sample["metrics"], "http_reqs", "{scenario:reads}") is None
    assert report.qps_of(sample, "{scenario:reads}") is None
    assert report.err_of(sample, "{scenario:reads}") is None
    rows = report.rows("avalanche", [sample])
    by_metric = {r[1]: r for r in rows}
    assert by_metric["http_req_duration{scenario:reads}"][2] is None
    assert by_metric["http_req_duration{scenario:reads}"][5] is None


def test_scenario_window_is_separate_from_full_run_rate():
    sample = run({
        "http_req_duration{scenario:reads}": {"med": 11, "p(99)": 40},
        "http_reqs{scenario:reads}": {"rate": 9, "count": 1800},
        "http_req_failed{scenario:reads}": {"rate": 0},
    }, extra={"scenarioWindows": {"{scenario:reads}": 90}})
    assert report.qps_of(sample, "{scenario:reads}") == 9
    assert report.scenario_qps(sample, "{scenario:reads}") == 20
    rows = report.rows("avalanche-jitter", [sample])
    scenario = [r for r in rows if r[1].startswith("scenario_qps")]
    assert scenario and scenario[0][2] == 20
    old = run({
        "http_req_duration": {"med": 1, "p(99)": 2},
        "http_reqs": {"rate": 11838, "count": 1},
        "http_req_failed": {"rate": 0},
    })
    assert report.scenario_qps(old, "") is None


def test_replace_keeps_other_cases_and_backfill_is_named():
    existing = ["keep\tmetric\t1\t1\t1\t0\t3", "drop\tmetric\t9\t1\t1\t0\t3"]
    merged = report.replace_cases(existing, ["drop"], ["drop\tmetric\t2\t1\t1\t0\t3"])
    assert merged == ["drop\tmetric\t2\t1\t1\t0\t3", "keep\tmetric\t1\t1\t1\t0\t3"]
    assert report.HISTORICAL_BACKFILL == ("hot-sort-db-noindex", "read-hot-naive-noindex")


if __name__ == "__main__":
    test_missing_tag_does_not_borrow_global()
    test_scenario_window_is_separate_from_full_run_rate()
    test_replace_keeps_other_cases_and_backfill_is_named()
    print("ok")

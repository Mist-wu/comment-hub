#!/usr/bin/env python3
"""查询一次压测时间窗口 [start, end] 内的服务端指标，合并进 k6 的结果 JSON。

用法：prom.py <run.json> <start_epoch> <end_epoch>
"""
import json
import sys
import urllib.parse
import urllib.request

PROM = "http://localhost:9095"


def q(expr, t):
    url = f"{PROM}/api/v1/query?" + urllib.parse.urlencode({"query": expr, "time": t})
    try:
        with urllib.request.urlopen(url, timeout=10) as r:
            res = json.load(r)["data"]["result"]
    except Exception as e:  # noqa: BLE001
        return {"error": str(e)}
    out = {}
    for s in res:
        label = ",".join(f"{k}={v}" for k, v in sorted(s["metric"].items())) or "value"
        v = float(s["value"][1])
        out[label] = None if v != v else v  # NaN → None
    return out


def main():
    path, start, end = sys.argv[1], int(sys.argv[2]), int(sys.argv[3])
    w = max(end - start, 10)
    W = f"{w}s"
    queries = {
        "kafka_lag_max": f"max_over_time(sum(kafka_consumergroup_lag)[{W}:2s])",
        "kafka_lag_max_by_group": f"max_over_time((sum by (consumergroup) (kafka_consumergroup_lag))[{W}:2s])",
        "e2e_p50_s": f"histogram_quantile(0.50, sum by (le, consumer) (increase(hub_e2e_latency_seconds_bucket[{W}])))",
        "e2e_p99_s": f"histogram_quantile(0.99, sum by (le, consumer) (increase(hub_e2e_latency_seconds_bucket[{W}])))",
        "cache_db_loads": f"sum(increase(hub_cache_db_load_total[{W}]))",
        "cache_db_load_rate_max": f"max_over_time(sum(rate(hub_cache_db_load_total[10s]))[{W}:2s])",
        "cache_results": f"sum by (result) (increase(hub_cache_total[{W}]))",
        "hikari_active_max": f"max_over_time(max(hikaricp_connections_active{{application=\"comment-service\"}})[{W}:2s])",
        "hikari_pending_max": f"max_over_time(max(hikaricp_connections_pending{{application=\"comment-service\"}})[{W}:2s])",
        "tomcat_busy_max": f"max_over_time(max(tomcat_threads_busy_threads{{application=\"comment-service\"}})[{W}:2s])",
        "mysql_qps_avg": f"sum(increase(mysql_global_status_queries[{W}])) / {w}",
        "mysql_row_lock_waits": f"sum(increase(mysql_global_status_innodb_row_lock_waits[{W}]))",
        "mysql_row_lock_time_ms": f"sum(increase(mysql_global_status_innodb_row_lock_time[{W}]))",
        "mysql_slow_queries": f"sum(increase(mysql_global_status_slow_queries[{W}]))",
        "redis_ops_avg": f"sum(increase(redis_commands_processed_total[{W}])) / {w}",
        "ai_calls": f"sum by (task, outcome) (increase(hub_ai_calls_total[{W}]))",
        "summary_results": f"sum by (result) (increase(hub_summary_total[{W}]))",
        "like_flush_rows": f"sum(increase(hub_like_flush_rows_sum[{W}]))",
        "like_flush_events": f"sum(increase(hub_like_flush_batch_size_sum[{W}]))",
    }
    with open(path) as fh:
        data = json.load(fh)
    data["prom"] = {k: q(v, end) for k, v in queries.items()}
    data["window"] = {"start": start, "end": end}
    with open(path, "w") as fh:
        json.dump(data, fh, indent=1, ensure_ascii=False)


if __name__ == "__main__":
    main()

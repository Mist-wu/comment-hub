#!/usr/bin/env python3
"""从 bench/results/raw/*/run-*.json 生成对比表（Markdown），供 docs/benchmark.md 和 README 引用。

所有数字都是 3 次运行的中位数；表格里每一格都能在 raw 目录里找到对应的 JSON。
用法：render.py > bench/results/tables.md
"""
from report import err_of, load, med, qps_of


def http(case, tag="", *, traffic_tag=None):
    """返回 (全程 qps, p50, p99, err%)。tag 形如 '{scenario:reads}'。

    全程 QPS 用 k6 的 rate。带 tag 时缺了同名 http_reqs / http_req_failed 就留空，
    不用无 tag 的总量填进这一行。
    """
    rs = load(case)
    if not rs:
        return None
    if traffic_tag is None:
        traffic_tag = tag
    durations = [r["metrics"].get("http_req_duration" + tag, {}) for r in rs]
    return (med(qps_of(r, traffic_tag) for r in rs), med(d.get("med") for d in durations),
            med(d.get("p(99)") for d in durations), med(err_of(r, traffic_tag) for r in rs))


def prom(case, key, label=None):
    rs = load(case)
    vals = []
    for r in rs:
        s = (r.get("prom") or {}).get(key) or {}
        if "error" in s:
            continue
        if label is None:
            v = sum(x for x in s.values() if x is not None) if s else None
        else:
            v = next((x for k, x in s.items() if label in k), None)
        vals.append(v)
    return med(vals)


def f(x, nd=1, unit=""):
    if x is None:
        return "-"
    return f"{x:,.{nd}f}{unit}"


def ratio(a, b):
    if not a or not b:
        return "-"
    return f"{a / b:.1f}×"


def table(head, rows):
    out = ["| " + " | ".join(head) + " |", "|" + "|".join("---" for _ in head) + "|"]
    out += ["| " + " | ".join(str(c) for c in r) + " |" for r in rows]
    return "\n".join(out)


def section(title, body):
    print(f"\n### {title}\n\n{body}\n")


def lag(case):
    # kafka-exporter 在没有积压时偶尔给出 -5 这样的负数（offset 采样先后），按 0 显示
    v = prom(case, "kafka_lag_max")
    return "0" if v is not None and v <= 0 else f(v, 0)


def main():
    # ---------- 分页 ----------
    if load("pagination"):
        rows = []
        for p in (1, 100, 1000):
            o = http("pagination", f"{{scenario:offset_p{p}}}")
            c = http("pagination", f"{{scenario:cursor_p{p}}}")
            rows.append([f"第 {p} 页", f(o[1], 2), f(o[2], 2), f(c[1], 2), f(c[2], 2), ratio(o[2], c[2])])
        section("LIMIT offset vs 游标分页（视频 1，约 151 万条评论，10 VU，不走缓存）",
                table(["页码", "offset P50 ms", "offset P99 ms", "游标 P50 ms", "游标 P99 ms", "P99 offset÷游标"], rows))

    # ---------- 通用 A/B ----------
    def ab(title, pairs, tag="", extra=()):
        rows = []
        for name, case in pairs:
            h = http(case, tag)
            if h is None:
                continue
            row = [name, f(h[0], 0), f(h[1], 2), f(h[2], 1), f(h[3], 2)]
            row.extend(fn(case) for _, fn in extra)
            rows.append(row)
        if rows:
            section(title, table(["版本", "QPS", "P50 ms", "P99 ms", "错误率 %"] + [name for name, _ in extra], rows))

    ab("最热排序：实时 ORDER BY like_count vs 定时写 ZSet（前 100 个视频，50 VU）",
       [("实时 ORDER BY，无 idx_video_like（filesort）", "hot-sort-db-noindex"),
        ("实时 ORDER BY，有覆盖索引 idx_video_like", "hot-sort-db"), ("定时写 ZSet", "hot-sort-zset")], extra=[("MySQL 平均 QPS", lambda c: f(prom(c, "mysql_qps_avg"), 0))])

    # 击穿
    rows = []
    for name, case in [("不处理", "breakdown-none"), ("互斥锁", "breakdown-mutex"), ("逻辑过期", "breakdown-logical")]:
        h = http(case, "{scenario:reads}", traffic_tag="")
        if h is None:
            continue
        rows.append([name, f(h[0], 0), f(h[1], 2), f(h[2], 1), f(h[3], 2),
                     f(prom(case, "cache_db_loads"), 0), f(prom(case, "hikari_active_max"), 0), f(prom(case, "hikari_pending_max"), 0)])
    if rows:
        section("缓存击穿：热点 key 每 10 秒过期一次（300 VU 打同一个 key，回源是 151 万行 filesort）",
                table(["版本", "全程 QPS", "P50 ms", "P99 ms", "错误率 %", "60 秒内回源次数", "连接池 active 峰值", "连接池 pending 峰值"], rows)
                + "\n\n这批 raw 没有按场景拆开的 `http_reqs`。QPS 是整次运行的请求速率。P50 / P99 来自 `scenario:reads`。")

    # 穿透
    rows = []
    for name, case in [("不处理", "penetration-none"), ("缓存空值", "penetration-null-value"), ("布隆过滤器", "penetration-bloom")]:
        hm = http(case, "{name:missing}")
        hn = http(case, "{name:normal}")
        if hm is None:
            continue
        rows.append([name, f(http(case)[0], 0), f(hm[2], 1), f(hn[2], 1), f(prom(case, "mysql_qps_avg"), 0)])
    if rows:
        section("缓存穿透：一半请求查不存在的视频（200 VU）",
                table(["版本", "总 QPS", "不存在视频 P99 ms", "正常视频 P99 ms", "MySQL 平均 QPS"], rows))

    # 雪崩
    rows = []
    for name, case in [("TTL 不抖动", "avalanche-nojitter"), ("TTL + 0~30 秒抖动", "avalanche-jitter")]:
        h = http(case, "{scenario:reads}", traffic_tag="")
        if h is None:
            continue
        rows.append([name, f(h[0], 0), f(h[1], 2), f(h[2], 1), f(h[3], 2),
                     f(prom(case, "cache_db_load_rate_max"), 0), f(prom(case, "hikari_pending_max"), 0)])
    if rows:
        section("缓存雪崩（历史脚本观测，全程 QPS，不是 5 秒写完 3000 个 key）",
                table(["版本", "全程 QPS", "P50 ms", "P99 ms", "错误率 %", "回源速率峰值 /s", "连接池 pending 峰值"], rows)
                + "\n\n上表来自旧脚本的 raw。QPS 是整次运行的请求速率，含预热。"
                  "raw 里没有成功预热的唯一 key 数，不能证明 3000 个 key 在 5 秒内写完。"
                  "新脚本把预热上限记成 30 秒，读从第 10 秒开始、持续 90 秒；"
                  "有 `extra.scenarioWindows` 时场景 QPS 另算。这次没有新的实测。")

    ab("热 key：所有请求打同一个 key（200 VU）",
       [("只用 Redis", "hotkey-redis"), ("热 key 进本地 Caffeine", "hotkey-local")])

    # ---------- 四个主场景 ----------
    def main_ab(title, naive, opt, tag="", extra=(), pre=None):
        cases = [("朴素版", naive), ("优化版", opt)]
        if pre:
            cases.insert(0, ("朴素版（修慢 SQL 之前，无 idx_video_like）", pre))
        ab(title, cases, tag, extra)

    main_ab("场景 1：热点视频读评论（Zipf 流量，200 VU，60 秒）", "read-hot-naive", "read-hot-optimized", pre="read-hot-naive-noindex",
            extra=[("MySQL 平均 QPS", lambda c: f(prom(c, "mysql_qps_avg"), 0)),
                   ("连接池 pending 峰值", lambda c: f(prom(c, "hikari_pending_max"), 0))])
    main_ab("场景 2：突发写评论（10 秒内拉到 3000/s，保持 30 秒）", "write-burst-naive", "write-burst-optimized",
            extra=[("Kafka 积压峰值", lambda c: lag(c)),
                   ("计数端到端 P99 s", lambda c: f(prom(c, "e2e_p99_s", "consumer=counter"), 2)),
                   ("审核端到端 P99 s", lambda c: f(prom(c, "e2e_p99_s", "consumer=moderation"), 2)),
                   ("行锁等待次数", lambda c: f(prom(c, "mysql_row_lock_waits"), 0))])
    main_ab("场景 3：点赞风暴（200 VU 点同一条评论，30 秒）", "like-storm-naive", "like-storm-optimized",
            extra=[("行锁等待次数", lambda c: f(prom(c, "mysql_row_lock_waits"), 0)),
                   ("行锁等待总时长 ms", lambda c: f(prom(c, "mysql_row_lock_time_ms"), 0)),
                   ("Kafka 积压峰值", lambda c: lag(c)),
                   ("落库端到端 P99 s", lambda c: f(prom(c, "e2e_p99_s", "consumer=like-flush"), 2)),
                   ("落库事件 / UPDATE 行数", lambda c: f"{f(prom(c, 'like_flush_events'), 0)} / {f(prom(c, 'like_flush_rows'), 0)}")])
    main_ab("场景 4：模型故障（读 800/s + 写 30/s，第 30~120 秒 mock-llm 延迟 10 秒、错误率 50%）",
            "llm-fault-naive", "llm-fault-optimized", tag="{scenario:reads}",
            extra=[("写接口 P99 ms", lambda c: f((http(c, "{scenario:writes}") or [None] * 4)[2], 1)),
                   ("Tomcat 忙线程峰值", lambda c: f(prom(c, "tomcat_busy_max"), 0))])


if __name__ == "__main__":
    main()

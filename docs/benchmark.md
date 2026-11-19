# 压测报告

所有数字都来自同一台机器、同一份数据，用 k6 压测，每个用例跑 3 次取中位数。

- 每一格都能在 `bench/results/raw/<用例>/run-N.json` 里找到原始值。
- 表格由 `bench/render.py` 生成，见 `bench/results/tables.md`。
- 服务端指标（连接池、Kafka 积压、行锁等待等）在每次运行结束后从 Prometheus 按该次的时间窗口取，同样取中位数。

朴素版和优化版只差运行时开关（`/admin/toggles`），是同一个进程、同一份数据。每个用例的开关见 `bench/all.sh`。

## 环境

| 项 | 值 |
|---|---|
| 机器 | Apple M5，10 核，16 GB，macOS 26.6.2 |
| 容器 | Colima（vz 虚拟机，virtiofs）6 vCPU / 10 GiB / 100 GiB 磁盘，Docker 29.5.2 |
| 压测端 | k6 v2.4.0，跑在宿主机上；场景 4 和复盘脚本的 k6 跑在 VM 内的容器里（见下文“注意”） |
| JDK | Temurin 21.0.13，G1；comment-service `-Xmx1g`，Tomcat 最大 200 线程，Hikari 30 连接 |
| MySQL | 8.4.3，buffer pool 2G，`innodb_flush_log_at_trx_commit=1`，`sync_binlog=1`，REPEATABLE READ |
| Redis | 7.4.1，单实例 |
| Kafka | 3.8.1，KRaft 单节点，每个 topic 3 个分区 |
| 模型 | 全部压测都打 mock-llm（默认延迟 300±200ms），不调真模型；真模型的评估和费用见 [cost.md](cost.md) |

数据是**生成的**（`datagen`）：

- 1 万个视频、1000 万条评论、5032 万个点赞；
- 视频热度服从 Zipf(s=1.1)：前 1% 的视频拿走 64.8% 的评论，1 号视频一个就有 151 万条；
- 视频内部的点赞也服从 Zipf，单条评论最多 92.6 万个赞；
- 表空间：数据 3.74 GB，索引 3.3 GB。

所有服务和压测端在同一台机器上，互相抢 CPU，所以这里的绝对 QPS 只用来做朴素版和优化版的相对比较，不代表线上容量。

## 结论速览

| 问题 | 朴素版 → 优化版 | 关键数字 |
|---|---|---|
| 热点视频读评论 | 直查 MySQL → Redis + 本地缓存 + ZSet + 计数器 | QPS 250 → 9,680（38.7×），P99 2,400ms → 52ms |
| 点赞风暴（同一条评论） | 一个事务里 INSERT + UPDATE 热点行 → Redis INCR + Kafka 合并落库 | QPS 1,230 → 6,054（4.9×），行锁等待 38,136 次 → 0 |
| 模型故障（延迟 10 秒 + 50% 错误） | 同步等模型、无超时 → deadline + 异步摘要 + 舱壁 + 熔断 | 读 P99 18,879ms → 5.3ms，Tomcat 忙线程 200 → 6 |
| 深分页（第 1000 页） | `LIMIT offset` → 游标 | P99 174ms → 3.8ms（46×） |
| 最热排序（慢 SQL） | filesort → 覆盖索引 | QPS 7 → 8,542，单条 SQL 1309ms → 0.245ms |
| 缓存击穿 | 不处理 → 互斥锁 | QPS 2,127 → 14,519，60 秒内回源 2,187 次 → 6 次 |
| 缓存穿透 | 不处理 → 布隆过滤器 | MySQL QPS 5,216 → 305 |
| 突发写 | 请求内同步副作用 → 写库 + 发事件 | **吞吐没有提升**（都卡在磁盘 fsync），热点行锁等待 16,787 → 0 |

下面每一节都写了怎么复现、看到了什么，以及没有达到预期的地方。

---

## 1. MySQL

### 1.1 LIMIT offset vs 游标分页

```bash
RUNS=3 bench/run.sh pagination bench/k6/10-pagination.js
```

### LIMIT offset vs 游标分页（视频 1，约 151 万条评论，10 VU，不走缓存）

| 页码 | offset P50 ms | offset P99 ms | 游标 P50 ms | 游标 P99 ms | P99 offset÷游标 |
|---|---|---|---|---|---|
| 第 1 页 | 1.21 | 3.76 | 1.41 | 3.79 | 1.0× |
| 第 100 页 | 12.36 | 24.54 | 1.38 | 3.72 | 6.6× |
| 第 1000 页 | 113.89 | 174.14 | 1.35 | 3.78 | 46.0× |

游标的耗时和页码无关，offset 的耗时和页码成正比。EXPLAIN ANALYZE（`bench/results/explain.md`）给出了原因：

| SQL | 读取行数 | 实际耗时 |
|---|---|---|
| offset 第 1 / 100 / 1000 页 | 20 / 2,065 / 20,614 | 0.35 / 1.4 / 13.7 ms |
| 游标，任意页 | 20 | 0.06–0.07 ms |

两者的执行计划一样，都是 `idx_video_created` 的 `Backward index scan`。区别在于 offset 要先读出并丢掉前面所有的行，而且每一行都要回表判断 `status`。

### 1.2 慢查询：最热排序

慢查询日志里，按总耗时排第一的是最热榜定时任务的 `ORDER BY like_count DESC, id DESC LIMIT 1000`：记录了 794 次，平均扫描 218 万行。分析过程和覆盖索引的设计见 [design.md 2.4](design.md#24-慢查询)。

| | 修复前 | 修复后 |
|---|---|---|
| 执行计划 | `idx_video_created` + `Using filesort` | `idx_video_like`，`Backward index scan; Using index` |
| 单条 SQL（视频 1，162 万条评论） | 1,309 ms，读 162 万行 | **0.245 ms**，读 1,036 行 |
| 100 个视频的整次重建 | 16 秒 | **0.4 秒** |
| 代价 | — | 索引 314 MB，在线建索引 8 秒 |

执行计划原文见 `bench/results/slow-sql/explain.md`。

### 1.3 最热排序：实时 ORDER BY vs 定时写 ZSet

```bash
bench/run.sh hot-sort-db bench/k6/11-hot-sort.js MODE=DB
bench/run.sh hot-sort-zset bench/k6/11-hot-sort.js MODE=ZSET
```

### 最热排序（前 100 个视频，50 VU，不走页缓存）

| 版本 | QPS | P50 ms | P99 ms | 错误率 % | MySQL 平均 QPS |
|---|---|---|---|---|---|
| 实时 ORDER BY，无 idx_video_like（filesort） | 7 | 3,013.86 | 24,848.1 | 25.76 | 9 |
| 实时 ORDER BY，有覆盖索引 idx_video_like | 8,542 | 5.16 | 15.2 | 0.00 | 8,610 |
| 定时写 ZSet | 8,983 | 5.06 | 12.8 | 0.00 | 8,974 |

第一行是修复慢 SQL 之前测的。加了覆盖索引以后，**实时排序和 ZSet 在首页上已经没有差别**：两条路径每个请求都查一次 MySQL，一个按索引范围扫 20 行，一个按主键取 20 行，代价相当。ZSet 剩下的好处是：

- 深分页：ZSet 用 `ZREVRANGE` 直接定位，代价是 O(log n)；按索引翻页要先跳过前面所有的行。
- 点赞数频繁变化时，不用每次去移动 `idx_video_like` 里的索引项。

最大的收益来自修复慢 SQL，不是来自换存储。这是做完压测才知道的。

### 1.4 点赞：唯一键、事务、热点行

点赞幂等靠 `UNIQUE (user_id, comment_id)` 加 `INSERT IGNORE`，隔离级别、MVCC 和热点行的分析见 [design.md 2.2–2.3](design.md#22-点赞的唯一键和幂等)。压测数字见下面的场景 3。

---

## 2. Redis

> **注意**：击穿、穿透、雪崩三组是在修复最热排序慢 SQL **之前**跑的。击穿的复现依赖“回源是一次昂贵的 filesort”：修复后同样的回源只要 0.25ms，就看不出差别了。三组内部的对比条件一致；同一时段里，同步日志锁和每分钟一次的慢 SQL 对三个版本的影响也是一样的。

### 2.1 缓存击穿

```bash
for m in NONE MUTEX LOGICAL; do bench/run.sh "breakdown-${m,,}" bench/k6/12-breakdown.js MODE=$m; done
```

### 缓存击穿：热点 key 每 10 秒过期一次（300 VU 打同一个 key，回源是 151 万行 filesort）

| 版本 | QPS | P50 ms | P99 ms | 错误率 % | 60 秒内回源次数 | 连接池 active 峰值 | 连接池 pending 峰值 |
|---|---|---|---|---|---|---|---|
| 不处理 | 2,127 | 34.89 | 3,241.8 | 1.52 | 2,187 | 30 | 169 |
| 互斥锁 | 14,519 | 16.10 | 45.0 | 0.00 | 6 | 1 | 0 |
| 逻辑过期 | 14,544 | 17.94 | 52.9 | 0.00 | 9 | 2 | 0 |

- **不处理时**：60 秒里 key 过期 6 次，却回源了 2,187 次，连接池被占满。P99 卡在 3.2 秒，正好是 Hikari 的连接超时，报错的都是拿不到连接的请求。
- **互斥锁**：每次过期只回源一次（6 次）。
- **逻辑过期**：多出来的 3 次是冷启动和过期判定的竞争。它过期后先返回旧值，P99 比互斥锁略高，因为后台重建时还有一次大 filesort 在抢资源。

完整的事故复盘（Grafana 曲线、连接池日志、按秒统计）见 [incidents/03](incidents/03-hot-key-breakdown.md)。

### 2.2 缓存穿透

### 缓存穿透：一半请求查不存在的视频（200 VU）

| 版本 | 总 QPS | 不存在视频 P99 ms | 正常视频 P99 ms | MySQL 平均 QPS |
|---|---|---|---|---|
| 不处理 | 9,757 | 64.0 | 65.0 | 5,216 |
| 缓存空值 | 16,685 | 39.0 | 43.2 | 936 |
| 布隆过滤器 | 18,776 | 18.7 | 48.3 | 305 |

- 不存在的视频 ID 是随机的，缓存空值只对“重复出现的 ID”有效，所以 MySQL 仍有 936 QPS。
- 布隆过滤器在入口拦截，连 Redis 都不查，MySQL 降到 305 QPS，剩下的是正常视频的回源。

### 2.3 缓存雪崩

### 缓存雪崩：3000 个 key 在 5 秒内写入、TTL 30 秒，之后均匀读 90 秒（200 VU）

| 版本 | QPS | P50 ms | P99 ms | 错误率 % | 回源速率峰值 /s | 连接池 pending 峰值 |
|---|---|---|---|---|---|---|
| TTL 不抖动 | 11,838 | 11.55 | 51.4 | 0.00 | 567 | 131 |
| TTL + 0~30 秒抖动 | 13,029 | 12.03 | 38.8 | 0.00 | 325 | 146 |

抖动把回源速率的峰值从 567/s 压到了 325/s，P99 从 51ms 降到 39ms。但效果没有预期大，连接池 pending 峰值也没降。原因有两点：

- 这 3000 个 key 对应 3000 个不同的视频，每个视频的回源都很便宜（走索引取 20 行）；
- 每个 key 都有互斥锁，同一个 key 不会重复回源。

真正危险的雪崩，是“大量昂贵的 key 同时过期”或“Redis 整个不可用”，这里没有覆盖到。

### 2.4 热 key：本地缓存

### 热 key：所有请求打同一个 key（200 VU）

| 版本 | QPS | P50 ms | P99 ms | 错误率 % |
|---|---|---|---|---|
| 只用 Redis | 18,701 | 9.73 | 25.0 | 0.00 |
| 热 key 进本地 Caffeine | 24,896 | 7.28 | 20.4 | 0.00 |

本地缓存省掉了一次 Redis 往返和反序列化，QPS 提高 33%。在这台机器上，Redis 单 key 每秒近 2 万次读并不是瓶颈，瓶颈在 comment-service 的 HTTP 处理上。所以这里测出来的是“少一跳”的收益。真正的“Redis 单分片打满”需要多实例的 comment-service 和更高的并发，这台机器做不到。

---

## 3. 四个主场景

```bash
bench/all.sh main
```

### 场景 1：热点视频读评论

### 场景 1：热点视频读评论（Zipf 流量，200 VU，60 秒）

| 版本 | QPS | P50 ms | P99 ms | 错误率 % | MySQL 平均 QPS | 连接池 pending 峰值 |
|---|---|---|---|---|---|---|
| 朴素版（修慢 SQL 之前，无 idx_video_like） | 50 | 3,003.11 | 22,526.1 | 50.89 | 53 | 170 |
| 朴素版 | 250 | 655.13 | 2,400.1 | 0.02 | 441 | 170 |
| 优化版 | 9,680 | 18.65 | 51.6 | 0.00 | 2,107 | 0 |

**朴素版**：

- 不走缓存；
- 最热排序实时 `ORDER BY`；
- 评论数实时 `COUNT(*)`（1 号视频 151 万行，覆盖索引扫一遍要 117ms）；
- 不拦截不存在的视频。

**优化版**：

- 首页走 Redis，热 key 再进一层本地缓存；
- 最热排序走 ZSet；
- 评论数用计数器；
- 布隆过滤器在入口拦截不存在的视频。

修复慢 SQL 后，朴素版从 50 QPS 涨到 250 QPS，但连接池依然被打满：`COUNT(*)` 和深分页仍然很贵。

### 场景 2：突发写评论

### 场景 2：突发写评论（10 秒内拉到 3000/s，保持 30 秒）

| 版本 | QPS | P50 ms | P99 ms | 错误率 % | Kafka 积压峰值 | 计数端到端 P99 s | 审核端到端 P99 s | 行锁等待次数 |
|---|---|---|---|---|---|---|---|---|
| 朴素版 | 1,551 | 517.36 | 977.6 | 0.00 | 0 | - | - | 16,787 |
| 优化版 | 1,663 | 413.36 | 1,123.0 | 0.00 | 8,900 | 0.24 | 9.84 | 0 |

**这一组没有达到预期，写在前面：两个版本的吞吐几乎一样，都没打到 3000/s。**

排查过程：

1. 第一次测，优化版 P50 是 476ms，而单条请求只要 3ms。jstack 显示 200 个 Tomcat 线程里 189 个卡在 logback 的同步 appender 锁上。改成异步 appender 后这个瓶颈消失。
2. 第二次的瓶颈换成了 MySQL。`performance_schema` 显示 INSERT 平均 7–17ms，VM 的 iowait 28%。每次提交都要刷 redo log 和 binlog（`innodb_flush_log_at_trx_commit=1`、`sync_binlog=1`），这台机器的 Colima VM 每秒只能完成 1200–1500 次提交。连接池从 30 加到 100 没有任何改善。
3. consumer 逐条处理时，每条事件一次提交，和写接口抢 fsync。改成按批合并后，突发写期间 consumer 的提交次数从 4.8 万降到 3 千。但前台写入的上限是 INSERT 本身，这一步也没能提高吞吐。

所以优化版在这个场景里的收益不在吞吐，而在以下几点：

- **热点行**：朴素版每条评论都在请求里执行 `UPDATE video SET comment_count = comment_count + 1`。1 号视频拿到 15% 的写入，那一行产生了 16,787 次行锁等待。优化版在请求里只 INSERT，计数由 consumer 合并成“一批一条 UPDATE”，行锁等待为 0。
- **请求不依赖模型**：朴素版在请求里同步调审核。这一组网关正常，被限流的请求会立刻降级到规则，所以没有拖慢请求。一旦模型变慢，结果就是场景 4。
- **一致性窗口**：评论数在 0.24 秒（P99）内跟上。审核有积压，P99 9.8 秒，因为模型 QPS 预算是 50/s，超出部分降级到规则，见 [design.md 第 4 节](design.md#4-kafka)。

要突破这个上限，可以放宽持久性，或者先写 Kafka、由 consumer 批量 INSERT。两种都有代价，本项目没有做，见 [design.md 2.5](design.md#25-写入的上限在磁盘)。

### 场景 3：点赞风暴

### 场景 3：点赞风暴（200 VU 点同一条评论，30 秒）

| 版本 | QPS | P50 ms | P99 ms | 错误率 % | 行锁等待次数 | 行锁等待总时长 ms | Kafka 积压峰值 | 落库端到端 P99 s | 落库事件 / UPDATE 行数 |
|---|---|---|---|---|---|---|---|---|---|
| 朴素版 | 1,230 | 153.60 | 304.5 | 0.00 | 38,136 | 881,840 | 0 | - | - / - |
| 优化版 | 6,054 | 29.23 | 101.5 | 0.00 | 0 | 0 | 120 | 0.04 | 182,904 / 3,513 |

- **朴素版**在一个事务里 `INSERT IGNORE` 点赞行，再 `UPDATE comment SET like_count = like_count + 1`。所有事务排队等同一行的 X 锁，30 秒里累计等锁 882 秒，相当于平均 29 个事务一直在排队。
- **优化版**只 `INSERT IGNORE` 点赞行（不同用户写不同的行，没有热点），计数走 Redis `INCRBY`，再由 consumer 合并落库：18.3 万个事件合并成 3,513 条 UPDATE，压缩比 52:1。落库端到端延迟 P99 40ms，这就是 MySQL 里 `like_count` 落后于 Redis 的窗口。对账方法见 [design.md 3.4](design.md#34-点赞计数redis-incr--kafka-批量落库)。

### 场景 4：模型故障

```bash
# 第 30–120 秒，mock-llm 延迟 10 秒、错误率 50%
K6_DOCKER=1 bench/run.sh llm-fault-optimized bench/k6/04-llm-fault.js
```

### 场景 4：模型故障（读 800/s + 写 30/s，第 30~120 秒 mock-llm 延迟 10 秒、错误率 50%）

| 版本 | 读 QPS | 读 P50 ms | 读 P99 ms | 错误率 % | 写接口 P99 ms | Tomcat 忙线程峰值 |
|---|---|---|---|---|---|---|
| 朴素版 | 593 | 0.69 | 18,879.1 | 0.00 | 9,775.6 | 200 |
| 优化版 | 798 | 0.67 | 5.3 | 0.00 | 7.4 | 6 |

- **朴素版**：读评论时同步等摘要，摘要同步等模型，各层都不设超时。Tomcat 200 个线程全部卡住，读 P99 18.9 秒，发评论也要等 9.8 秒，读流量只能跑到目标的 74%。
- **优化版**：摘要的 deadline 是 150ms，网关异步生成摘要，读请求根本不等模型。

熔断器的状态变化（3 次运行一致）：

| 时刻 | 事件 |
|---|---|
| 第 30 秒 | 注入故障 |
| 第 33 秒 | CLOSED → OPEN，打开用了 **3 秒** |
| 第 43 秒 | OPEN → HALF_OPEN，试探失败，回到 OPEN |
| 每 30 秒左右 | 重复一次试探 |
| 第 120 秒 | 故障恢复 |
| 第 121.3 秒 | HALF_OPEN → CLOSED，恢复后 **1.3 秒**关闭 |

完整的线程池耗尽复盘（jstack、jstat）见 [incidents/02](incidents/02-no-timeout.md)。

> **注意**：场景 4 的 k6 跑在 Colima VM 内的容器里，直接访问 compose 网络（`K6_DOCKER=1`）。朴素版会堆积大量挂起的连接，宿主机到 VM 的端口转发在这种负载下两次断开，连 Docker socket 也一起断了。其它场景的 k6 跑在宿主机上，所以场景 4 的绝对延迟和其它场景不能直接比较。

---

## 4. JVM

压测期间开着 GC 日志（`-Xlog:gc*`，见 `docker-compose.yml`），用 grep 和 awk 统计 comment-service 的停顿（`bench/results/gc-comment-service.txt`）：

```bash
grep -E "Pause (Young|Full|Remark|Cleanup).*ms$" logs/gc-comment-service.log* \
  | awk '{t=$NF; sub("ms","",t); k=$4; if(k=="Young"||k=="Full") k=k" "$5; n[k]++; s[k]+=t; if(t+0>m[k]) m[k]=t+0}
         END{for(k in n) printf "%-20s n=%5d avg=%6.2fms max=%7.2fms\n", k, n[k], s[k]/n[k], m[k]}'
```

| 停顿 | 次数 | 平均 | 最大 |
|---|---|---|---|
| Young (Normal) | 1,788 | 8.7 ms | 156 ms |
| Young (Mixed) | 54 | 23.0 ms | 155 ms |
| Remark | 54 | 22.3 ms | 162 ms |
| Full | 2 | 129 ms | 201 ms |

这两次 Full GC 都是 `Heap Inspection Initiated GC`，是排查时执行 `jmap -histo:live` 触发的，不是内存不够。`jmap -histo:live` 会先做一次 Full GC，线上要慎用，用不带 `:live` 的版本即可。

`jmap -histo:live` 显示存活对象一共 53 MB：前几位是 `byte[]`（18.5 MB，主要是 Redis 和 JSON 的缓冲）和 String，没有可疑的业务对象堆积。堆上限 1G 绰绰有余。所有场景的 P99 都在几十毫秒，GC 不是瓶颈。

## 5. 复现

```bash
docker compose up -d
docker compose --profile datagen run --rm datagen       # 约 5 分钟
bench/all.sh micro                                      # 专项对比，约 60 分钟
bench/all.sh main                                       # 四个主场景，约 40 分钟
.venv/bin/python bench/render.py > bench/results/tables.md
```

`bench/explain.sh` 收集 EXPLAIN ANALYZE。`scripts/incidents/0N-*.sh` 复现三篇事故复盘。

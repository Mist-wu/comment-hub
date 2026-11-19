# 设计说明

这份文档讲每个设计“为什么这样做”。“做了以后快了多少”见 [benchmark.md](benchmark.md)，数字都来自同一台机器上的压测。

## 1. 服务和数据流

```
            HTTP                        gRPC (deadline)
  client ──────────▶ comment-service ─────────────────▶ ai-gateway ──HTTP──▶ mock-llm / DeepSeek
                      │   │    │                           ▲
                      │   │    └─ CommentCreated / LikeChanged ─▶ Kafka ─▶ consumer
                      │   │                                              │  ├ counter    评论数 +1
                    MySQL Redis(+Caffeine)                               │  ├ cache      删首页缓存
                                                                         │  ├ moderation 调网关审核 ──┘
                                                                         │  └ like-flush 点赞数批量落库
```

trace ID 的传递：HTTP 头 `X-Trace-Id` → MDC → gRPC metadata `x-trace-id` → Kafka 消息头 `trace-id` → consumer MDC → 再次 gRPC → mock-llm 的 HTTP 头。所有服务日志 pattern 里都有 `[%X{traceId}]`，所以：

```bash
grep -h 3f9a1c... logs/*.log | sort
```

能按时间顺序看到一次发评论从入口、发事件、计数、审核到模型调用的全部日志。

## 2. MySQL

### 2.1 游标分页的索引

`KEY idx_video_created (video_id, created_at, id)`。最新排序是 `ORDER BY created_at DESC, id DESC`，`id` 放进索引是为了：

- 同一毫秒多条评论时排序稳定，游标 `(created_at, id)` 唯一定位一行；
- 二级索引叶子节点本来就带主键，把 `id` 显式写进索引定义，让 `(created_at, id) < (?, ?)` 的范围条件可以完整用上索引。

游标分页的 SQL：

```sql
SELECT ... FROM comment
WHERE video_id = ? AND status <> 3
  AND (created_at < ? OR (created_at = ? AND id < ?))
ORDER BY created_at DESC, id DESC LIMIT 20;
```

无论翻到第几页，都是从索引里的一个位置往后读 20 条。`LIMIT 20000, 20` 则要先按索引顺序读出 20020 行（还要回表判断 `status`），再丢掉前 20000 行，页码越深越慢。两者的 EXPLAIN 和耗时对比见 benchmark.md。

### 2.2 点赞的唯一键和幂等

`UNIQUE KEY uk_user_comment (user_id, comment_id)`，点赞用 `INSERT IGNORE`：影响行数 1 是新点赞，0 是重复点赞。客户端重试、用户连点、消息重放都不会多算，接口天然幂等。取消点赞是 `DELETE ... WHERE user_id = ? AND comment_id = ?`，同理。

### 2.3 朴素版点赞事务、隔离级别和热点行

朴素版在一个事务里做两件事：

```sql
BEGIN;
INSERT IGNORE INTO comment_like (user_id, comment_id) VALUES (?, ?);
UPDATE comment SET like_count = like_count + 1 WHERE id = ?;   -- 仅当上一句影响了 1 行
COMMIT;
```

为什么要放在一个事务里：两句必须一起成功或一起失败，否则会出现“点赞行有了，计数没加”或反过来。

隔离级别是 InnoDB 默认的 REPEATABLE READ。这里要分清两种读：

- **快照读**：普通 `SELECT`，通过 MVCC 读事务开始时的版本（undo log 链 + ReadView），不加锁，所以读评论列表不会被点赞阻塞。
- **当前读**：`UPDATE`、`SELECT ... FOR UPDATE`，读最新已提交版本并加锁。`like_count = like_count + 1` 是在最新值上加 1，所以不会丢更新——即使在 REPEATABLE READ 下，它也不会基于旧快照计算。

热点行就出在当前读上：`UPDATE` 要拿这一行的 X 锁（记录锁），一直持有到 `COMMIT`。同一条评论被 1 万个人同时点赞时，1 万个事务在这一行上排队，每个事务持锁的时间 ≈ 执行时间 + 提交时写 redo log 并 fsync 的时间（`innodb_flush_log_at_trx_commit = 1`）。吞吐上限大约是 `1 / 单个事务持锁时间`，和 CPU 核数无关；排队的事务还会占着数据库连接，连接池被耗尽后其它接口也跟着变慢。InnoDB 的死锁检测在大量线程等同一把锁时本身也会消耗大量 CPU。

优化版的思路是让热点行不再出现在请求路径上：点赞行照常写 MySQL（不同用户写不同的行，唯一键检查没有热点），计数改成 Redis `INCRBY`（单线程、内存操作，一次几微秒），再由 consumer 合并后批量 `UPDATE`。

### 2.4 慢查询

`deploy/mysql/my.cnf` 打开了慢查询日志，阈值 100ms；`log_slow_extra = 1` 会额外记录扫描行数、排序行数等。分析方法：

```bash
docker compose exec mysql sh -c 'cat /var/log/mysql/slow.log' > slow.log
# 按 SQL 指纹聚合：次数、总耗时、平均扫描行数
awk '/^# Query_time/{qt=$3; for(i=1;i<=NF;i++) if($i=="Rows_examined:") re=$(i+1); getline; while($0 ~ /^(SET|use)/) getline;
     s=$0; gsub(/[0-9]+/,"N",s); n[s]++; t[s]+=qt; r[s]+=re}
     END{for(s in n) printf "%6d %8.1fs avg_rows=%d  %s\n", n[s], t[s], r[s]/n[s], substr(s,1,120)}' slow.log | sort -k2 -rn | head
```

按总耗时排第一的不是请求路径上的 SQL，而是最热榜定时任务 `HotRankJob` 的 `ORDER BY like_count DESC, id DESC LIMIT 1000`：

- 记录了 794 次，平均每次扫描 218 万行；
- 每分钟跑一次（100 个视频，每次 7–16 秒），一直和前台请求抢 IO。

`idx_video_created` 只能用来按 `video_id` 过滤，排序要对整个视频的全部评论做 filesort。修复办法是加一个覆盖索引：

```sql
ALTER TABLE comment ADD KEY idx_video_like (video_id, like_count, id, status);
```

- `(video_id, like_count, id)` 和排序方向一致，倒序扫描读到 1000 行就停；
- `status` 放进索引是为了覆盖 `status <> 3` 的过滤，不用回表；
- `id` 是主键，二级索引的叶子节点本来就带着它，这里显式写出来是为了让它排在 `status` 前面，参与排序。

修复后，视频 1（162 万条评论）上这条 SQL 从 1309ms 降到 0.245ms，扫描行数从 162 万降到 1036；整次重建从 16 秒降到 0.4 秒。代价是索引占 314MB，而且点赞数落库时要移动索引项。点赞数是 consumer 合并后批量更新的，这个代价很小。执行计划的前后对比见 `bench/results/slow-sql/explain.md`。

### 2.5 写入的上限在磁盘

`innodb_flush_log_at_trx_commit = 1`、`sync_binlog = 1`，每次提交都要把 redo log 和 binlog 刷盘。在这台机器的 Colima VM 里，单条 INSERT（自动提交）平均 7–17ms，突发写打到每秒 1200–1500 条就到顶了：

- 连接池从 30 加到 100 也没有提升，组提交已经尽力了；
- 这时 VM 的 iowait 在 28% 左右。

所以写路径的优化目标不是“更快地写评论”，而是两件事：

1. **请求里只做一次提交**：写评论只 INSERT 一行、发一条消息。朴素版同步执行“+评论数、改审核状态”，一次请求三次提交。
2. **后台不和前台抢 fsync**：consumer 逐条处理时每条事件一次提交，突发写期间 consumer 的提交次数和前台一样多。改成按批合并后，一批（最多 500 条）只开一个事务，提交次数从 4.8 万降到 3 千。

如果要突破这个上限，可以放宽持久性（`innodb_flush_log_at_trx_commit = 2`，崩溃最多丢 1 秒），或者批量插入（先写 Kafka，由 consumer 合并 INSERT，代价是写后读不一致）。本项目都没有做，benchmark.md 记录的是 `= 1` 下的数字。

## 3. Redis

### 3.1 cache-aside：先写库，再删缓存

读：查缓存，没有就查库并回填。写：先更新数据库，再删除缓存（本项目由 consumer 收到 `CommentCreated` 后删）。

**为什么不先删缓存再写库？** 两个请求并发时：A 删了缓存 → B 读缓存未命中、读库读到旧值 → A 写库 → B 把旧值写回缓存。之后直到 TTL 过期，缓存里一直是旧数据。先写库再删缓存也有类似的竞争（B 读到旧值后、回填前，A 恰好完成写库和删缓存），但要求“读库 + 回填”比“写库 + 删缓存”还慢，概率低得多。

**为什么不直接更新缓存？** 一是并发写时两次更新的顺序无法保证，后写库的可能先写缓存，缓存最终停在旧值；二是首页缓存是“一页 20 条”的聚合结果，每来一条新评论就要重新算一遍整页，而大多数写入之后根本没人读——删除是惰性的，只有下一次读才付出重建成本。

**剩下的不一致窗口**：写库成功到 consumer 删掉缓存之间（Kafka 端到端延迟，压测里有数字），读到的是不含新评论的首页；再加上 TTL 兜底。对评论区来说，新评论晚几百毫秒出现在首页可以接受。

### 3.2 三个经典问题

| 问题 | 复现方式 | 修复 | 代码 |
|---|---|---|---|
| 击穿 | 所有请求打 1 号视频的最热首页（回源是一次大 filesort），每 10 秒让它过期一次 | 互斥锁（`SET NX PX` + Lua 校验 token 再删），只有一个请求回源，其余等 20ms 重读；或逻辑过期，过期后返回旧值、单线程异步重建 | `PageCache.withMutex / logical` |
| 穿透 | 一半请求查不存在的视频 ID | 缓存空值（60 秒）；或布隆过滤器在入口拦截 | `PageCache.write`、`VideoBloom` |
| 雪崩 | 5 秒内写入 3000 个 key，TTL 都是 30 秒 | TTL 加 0~30 秒随机抖动 | `PageCache.ttlSeconds` |

布隆过滤器只拦截，不确认：它说“不在”就一定不在；说“在”有 0.1% 的误判，误判的请求照常走缓存和数据库，结果是空页，不会出错。缓存空值对“同一个不存在的 ID 被反复查”有效，对随机 ID 无效，所以两种都做了，压测里能看到差别。

### 3.3 热 key 和本地缓存

单个 Redis key 的读 QPS 受限于 Redis 单线程和网络往返；最热的几个视频首页会把所有流量集中到一个 key、一个 Redis 实例。做法是每秒统计一次 key 的访问次数（`HotKeyDetector`），超过阈值的 key 在本机 Caffeine 里缓存 1 秒。代价是这 1 秒内各实例可能看到不同版本的首页，对评论区可以接受。

### 3.4 点赞计数：Redis INCR + Kafka 批量落库

1. `INSERT IGNORE` 点赞行（幂等）；
2. Lua：key 存在才 `INCRBY`，不存在返回 nil，由应用从 MySQL 读 `like_count` 初始化（`SET NX`）后再加——避免从 0 开始计数；
3. 发 `LikeChanged`（key = commentId，同一评论进同一分区）；
4. consumer 批量拉取（最多 500 条），按事件 ID 去重，按评论求和，一条评论一条 `UPDATE ... SET like_count = like_count + ?`，按 id 排序更新避免死锁；
5. 评论 ID 记入 `like:dirty` 集合，供对账使用。

**MySQL 和 Redis 的不一致窗口**：Redis 是实时值，MySQL 的 `like_count` 落后“Kafka 端到端延迟 + 批间隔”，正常情况下是几百毫秒，积压时会变大（压测记录了积压量和端到端延迟）。读接口用 Redis 值覆盖库里的值（`overlayLikes`），所以用户看到的总是 Redis 值。

**会出现永久不一致的情况**和对账：

- 点赞行写成功但进程在 `INCRBY` 前崩溃：Redis 和 MySQL 都少 1；
- Redis key 被淘汰后重新初始化时，还有未落库的事件：Redis 少算；
- 事件发送失败且 outbox 也写失败。

对账以 `comment_like` 的行数为准（唯一键保证一人一行），对 `like:dirty` 里的评论比较三方：`POST /admin/reconcile/likes?fix=true`。应在 Kafka 积压清零后执行，否则会把“正在路上”的差值误判为不一致。

## 4. Kafka

- 发评论只做两件事：写库、发 `CommentCreated` 后返回。发送失败（broker 不可用、超时）时把事件写进 `event_outbox`，定时重发。仍存在的窗口是“库提交后、发送前进程崩溃”，彻底解决需要把 outbox 写入和评论写入放进同一事务（事务性 outbox），代价是每次发评论多写一行；本项目选择了只在失败时写 outbox，并用对账兜底。
- 计数、审核、点赞落库都是批量消费：一次最多拉 500 条，去重和业务更新放在同一个事务里，按主键排序更新以避免死锁。审核要调模型，批内用虚拟线程并发调用，并发上限 32，和网关舱壁一致。逐条同步调用时，吞吐约等于 1 / 模型延迟（3 个分区约 7 条/秒），一次突发写就积压了 7.8 万条。
- 审核的吞吐最终受模型 QPS / token 预算限制。超出预算的部分由网关降级到关键词规则，积压能很快消化完，代价是突发期间大部分评论由规则审核。如果更在意审核质量，可以让 consumer 在网关限流时退避等待，代价是积压时间变长：按 50 QPS 算，12 万条要 40 分钟。
- 三个关注点（计数、删缓存、审核）拆成三个消费者组：审核依赖模型，可能慢、可能失败，不能拖住计数和删缓存；分开去重也保证了重放时不会因为“计数已经做过”而把审核一起跳过。
- 幂等：`consumed_event (consumer, event_id)` 主键。去重记录和业务更新在同一个数据库事务里：业务失败回滚时去重记录一起回滚，下次重试还能处理；业务提交后再收到同一事件，`INSERT IGNORE` 返回 0，直接跳过。删缓存本身是幂等操作，不需要去重表。
- 重试和死信：间隔 1 秒重试 3 次，仍失败投到 `{topic}-dlt`，保留原分区号和异常信息头。`scripts/replay-dlt.sh <topic>` 把死信发回原 topic。

## 5. 日志

所有服务共用 `common/src/main/resources/logback-spring.xml`，控制台和文件 appender 外面各包一层 `AsyncAppender`。

同步 appender 写日志要拿一把锁。突发写压测时，jstack 显示 200 个 Tomcat 线程里有 189 个卡在 `OutputStreamAppender.writeBytes` 上，写接口 P50 从 3ms 涨到 476ms。换成异步 appender 后，队列满了宁可丢日志，也不阻塞请求线程（`neverBlock = true`）。

## 6. ai-gateway

一次模型调用依次经过：

1. **限流**：两个令牌桶，一个按 QPS（每次取 1），一个按每分钟 token 预算（每次取“输入估算 + max_tokens”，调用后按实际用量补扣）。任一不够立即降级。
2. **熔断**：Resilience4j，最近 20 次调用里错误率 ≥50% 或慢调用（>2s）比例 ≥50% 时打开；10 秒后自动半开，放 5 个请求试探，成功率够就关闭。
3. **舱壁**：信号量限制在途调用数（默认 32），20ms 内拿不到许可就降级，不排队。网关的 gRPC 请求跑在虚拟线程上，模型慢的时候不会占满平台线程。
4. **超时**：HTTP 超时取 `min(配置值, 调用方 gRPC deadline 剩余时间)`。

降级：审核走关键词规则（宁可送人工复核，不放过明显违规），摘要直接不显示。网关对外永远返回结果而不是错误，`source` / `degrade_reason` 字段说明结果从哪来。

摘要按“视频 + 评论数档位”（<100、<1000、<1 万、<10 万、≥10 万）缓存 6 小时，档位内评论再多也不重新生成。读路径不等模型：未命中时启动一次后台生成，本次返回 `ready=false`；生成失败写 30 秒失败标记，模型挂掉期间读流量不会反复触发生成。

记账沿用 y2b-rs 的做法：每次 HTTP 调用（包括解析失败后的重试）在 `ai_call` 表里一行，开始时 `started`，结束后回填状态、输入 / 缓存命中 / 输出 token、耗时和费用。单价没配置时费用为 NULL，不估算。

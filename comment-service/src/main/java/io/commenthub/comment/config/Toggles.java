package io.commenthub.comment.config;

import org.springframework.stereotype.Component;

/**
 * 运行时开关。压测时通过 POST /admin/toggles 切换“朴素版 / 优化版”，不用重启进程，
 * 保证同一台机器、同一份数据、同一个 JVM 预热状态下做对比。
 */
@Component
public class Toggles {
    public enum HotMode { DB, ZSET }
    public enum Breakdown { NONE, MUTEX, LOGICAL }
    public enum Penetration { NONE, NULL_VALUE, BLOOM }
    public enum LikeMode { DB, REDIS }
    public enum CountMode { COUNT, COUNTER }

    public volatile HotMode hotMode = HotMode.ZSET;
    public volatile boolean cacheEnabled = true;
    public volatile Breakdown breakdown = Breakdown.MUTEX;
    public volatile Penetration penetration = Penetration.BLOOM;
    public volatile int cacheTtlSeconds = 300;
    /** TTL 随机抖动上限（秒），0 = 不抖动 */
    public volatile int ttlJitterSeconds = 60;
    public volatile boolean localCache = true;
    /** 1 秒窗口内访问次数超过这个值就算热 key，进本地缓存 */
    public volatile int hotKeyThreshold = 200;
    public volatile int localTtlMillis = 1000;
    public volatile LikeMode likeMode = LikeMode.REDIS;
    public volatile CountMode countMode = CountMode.COUNTER;
    /** 朴素版写：在请求里同步做计数、删缓存、调模型审核；优化版只写库 + 发事件 */
    public volatile boolean syncWrite = false;
    public volatile boolean summaryEnabled = true;
    /** 摘要 gRPC 调用的 deadline，0 = 不设（复盘 02 用） */
    public volatile int summaryDeadlineMs = 150;
    public volatile int summaryMinComments = 1000;
}

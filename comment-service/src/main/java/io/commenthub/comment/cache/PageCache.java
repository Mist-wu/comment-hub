package io.commenthub.comment.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import io.commenthub.comment.config.Toggles;
import io.commenthub.comment.domain.Page;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 评论首页缓存（cache-aside）。读：本地 Caffeine（仅热 key）→ Redis → MySQL。
 * 写：业务先写库，consumer 收到 CommentCreated 后删 Redis key（见 docs/design.md 为什么不先删、不直接更新）。
 *
 * 三个经典问题的开关都在 {@link Toggles} 里：
 * <ul>
 *   <li>击穿 breakdown：NONE 直接回源；MUTEX 只让一个请求回源；LOGICAL 逻辑过期，过期后返回旧值并异步重建。</li>
 *   <li>穿透 penetration：NULL_VALUE 缓存空结果；BLOOM 在入口用布隆过滤器拦掉不存在的视频（见 VideoBloom）。</li>
 *   <li>雪崩：TTL 加 0~ttlJitterSeconds 的随机抖动。</li>
 * </ul>
 */
@Component
public class PageCache {
    private static final Logger log = LoggerFactory.getLogger(PageCache.class);
    static final String NULL_MARK = "__null__";
    private static final int NULL_TTL_SECONDS = 60;

    private final StringRedisTemplate redis;
    private final ObjectMapper om;
    private final Toggles toggles;
    private final HotKeyDetector hotKeys;
    private final RedisMutex mutex;
    private final Cache<String, Page> local;
    private final ExecutorService rebuildPool = Executors.newVirtualThreadPerTaskExecutor();
    private final Counter localHit, redisHit, miss, dbLoad, lockWait;

    public PageCache(StringRedisTemplate redis, ObjectMapper om, Toggles toggles, HotKeyDetector hotKeys,
                     RedisMutex mutex, MeterRegistry meters) {
        this.redis = redis;
        this.om = om;
        this.toggles = toggles;
        this.hotKeys = hotKeys;
        this.mutex = mutex;
        this.local = Caffeine.newBuilder()
                .maximumSize(1_000)
                .expireAfter(new Expiry<String, Page>() {
                    public long expireAfterCreate(String k, Page v, long now) {
                        return TimeUnit.MILLISECONDS.toNanos(toggles.localTtlMillis);
                    }
                    public long expireAfterUpdate(String k, Page v, long now, long cur) {
                        return expireAfterCreate(k, v, now);
                    }
                    public long expireAfterRead(String k, Page v, long now, long cur) {
                        return cur;
                    }
                })
                .recordStats()
                .build();
        this.localHit = Counter.builder("hub.cache").tag("result", "local_hit").register(meters);
        this.redisHit = Counter.builder("hub.cache").tag("result", "redis_hit").register(meters);
        this.miss = Counter.builder("hub.cache").tag("result", "miss").register(meters);
        this.dbLoad = Counter.builder("hub.cache.db_load").register(meters);
        this.lockWait = Counter.builder("hub.cache.lock_wait").register(meters);
    }

    public Page get(String key, Supplier<Page> loader) {
        if (!toggles.cacheEnabled) {
            return loader.get();
        }
        hotKeys.record(key);
        if (toggles.localCache && hotKeys.isHot(key)) {
            Page p = local.getIfPresent(key);
            if (p != null) {
                localHit.increment();
                return p;
            }
            // Caffeine 的 get(key, fn) 会合并同一 key 的并发加载
            return local.get(key, k -> fromRedis(k, loader));
        }
        return fromRedis(key, loader);
    }

    private Page fromRedis(String key, Supplier<Page> loader) {
        return switch (toggles.breakdown) {
            case NONE -> plain(key, loader);
            case MUTEX -> withMutex(key, loader);
            case LOGICAL -> logical(key, loader);
        };
    }

    // ---------- 朴素版：未命中就回源 ----------
    private Page plain(String key, Supplier<Page> loader) {
        String raw = redis.opsForValue().get(key);
        if (raw != null) {
            redisHit.increment();
            return decode(raw);
        }
        miss.increment();
        Page p = load(loader);
        write(key, p);
        return p;
    }

    // ---------- 互斥锁：只有拿到锁的请求回源，其余短暂等待后重读 ----------
    private Page withMutex(String key, Supplier<Page> loader) {
        String lockKey = "lock:" + key;
        for (int attempt = 0; attempt < 100; attempt++) {
            String raw = redis.opsForValue().get(key);
            if (raw != null) {
                redisHit.increment();
                return decode(raw);
            }
            String token = mutex.tryLock(lockKey, Duration.ofSeconds(5));
            if (token != null) {
                try {
                    String again = redis.opsForValue().get(key); // double check
                    if (again != null) {
                        redisHit.increment();
                        return decode(again);
                    }
                    miss.increment();
                    Page p = load(loader);
                    write(key, p);
                    return p;
                } finally {
                    mutex.unlock(lockKey, token);
                }
            }
            lockWait.increment();
            sleep(20);
        }
        // 等了 2 秒还没拿到，说明重建异常慢；直接回源，宁可多打一次库也不让请求无限等
        log.warn("mutex wait timeout, load directly key={}", key);
        return load(loader);
    }

    // ---------- 逻辑过期：值里带过期时间，物理上不过期；过期后返回旧值，单线程异步重建 ----------
    private Page logical(String key, Supplier<Page> loader) {
        String raw = redis.opsForValue().get(key);
        if (raw == null) {
            // 冷启动没有旧值可返回，只能退回互斥锁
            miss.increment();
            return withMutexLogicalInit(key, loader);
        }
        redisHit.increment();
        if (!raw.startsWith("{\"e\":")) return decode(raw); // 切换模式前留下的普通值
        JsonNode node = readTree(raw);
        long expireAt = node.get("e").asLong();
        Page p = om.convertValue(node.get("d"), Page.class);
        if (System.currentTimeMillis() > expireAt) {
            String lockKey = "lock:" + key;
            String token = mutex.tryLock(lockKey, Duration.ofSeconds(10));
            if (token != null) {
                rebuildPool.submit(() -> {
                    try {
                        writeLogical(key, load(loader));
                    } catch (Exception e) {
                        log.warn("logical rebuild failed key={}", key, e);
                    } finally {
                        mutex.unlock(lockKey, token);
                    }
                });
            }
        }
        return p;
    }

    private Page withMutexLogicalInit(String key, Supplier<Page> loader) {
        String lockKey = "lock:" + key;
        for (int i = 0; i < 100; i++) {
            String token = mutex.tryLock(lockKey, Duration.ofSeconds(5));
            if (token != null) {
                try {
                    String again = redis.opsForValue().get(key);
                    if (again != null) return om.convertValue(readTree(again).get("d"), Page.class);
                    Page p = load(loader);
                    writeLogical(key, p);
                    return p;
                } finally {
                    mutex.unlock(lockKey, token);
                }
            }
            sleep(20);
            String raw = redis.opsForValue().get(key);
            if (raw != null) return om.convertValue(readTree(raw).get("d"), Page.class);
        }
        return load(loader);
    }

    // ---------- 公共 ----------
    private Page load(Supplier<Page> loader) {
        dbLoad.increment();
        return loader.get();
    }

    private void write(String key, Page p) {
        if (p.items().isEmpty()) {
            // 朴素版不缓存空结果 → 查不存在的视频每次都打到库上（穿透）
            if (toggles.penetration == Toggles.Penetration.NULL_VALUE) {
                redis.opsForValue().set(key, NULL_MARK, Duration.ofSeconds(NULL_TTL_SECONDS));
            }
            return;
        }
        redis.opsForValue().set(key, encode(p), Duration.ofSeconds(ttlSeconds()));
    }

    private void writeLogical(String key, Page p) {
        ObjectNode n = om.createObjectNode();
        n.put("e", System.currentTimeMillis() + ttlSeconds() * 1000L);
        n.set("d", om.valueToTree(p));
        // 物理 TTL 设成逻辑 TTL 的 10 倍，长期不访问的 key 仍会被回收
        redis.opsForValue().set(key, n.toString(), Duration.ofSeconds(ttlSeconds() * 10L));
    }

    /** 基础 TTL + 随机抖动，避免同一批写入的 key 在同一秒过期（雪崩）。 */
    int ttlSeconds() {
        int jitter = toggles.ttlJitterSeconds;
        return toggles.cacheTtlSeconds + (jitter > 0 ? ThreadLocalRandom.current().nextInt(jitter + 1) : 0);
    }

    public void evict(String key) {
        redis.delete(key);
        local.invalidate(key);
    }

    public int expireAll(String prefix, int seconds) {
        int n = 0;
        for (String k : scan(prefix)) {
            String raw = redis.opsForValue().get(k);
            if (raw != null && raw.startsWith("{\"e\":")) {
                ObjectNode node = (ObjectNode) readTree(raw);
                node.put("e", System.currentTimeMillis() + seconds * 1000L);
                redis.opsForValue().set(k, node.toString(), Duration.ofSeconds(toggles.cacheTtlSeconds * 10L));
            } else {
                redis.expire(k, Duration.ofSeconds(seconds));
            }
            n++;
        }
        local.invalidateAll();
        return n;
    }

    public int deleteAll(String prefix) {
        List<String> keys = scan(prefix);
        if (!keys.isEmpty()) redis.delete(keys);
        local.invalidateAll();
        return keys.size();
    }

    private List<String> scan(String prefix) {
        List<String> out = new ArrayList<>();
        try (Cursor<String> c = redis.scan(ScanOptions.scanOptions().match(prefix + "*").count(1000).build())) {
            c.forEachRemaining(out::add);
        }
        return out;
    }

    private Page decode(String raw) {
        if (NULL_MARK.equals(raw)) return new Page(List.of(), null, null);
        try {
            return om.readValue(raw, Page.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private String encode(Page p) {
        try {
            return om.writeValueAsString(p);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private JsonNode readTree(String raw) {
        try {
            return om.readTree(raw);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

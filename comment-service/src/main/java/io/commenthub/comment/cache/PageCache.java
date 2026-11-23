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
import jakarta.annotation.PreDestroy;
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
    /** 停机最多等这么久。慢回源可能停不掉，到点就返回，避免卡住 Spring 关闭。 */
    Duration shutdownWait = Duration.ofSeconds(5);
    private final Counter localHit, redisHit, miss, dbLoad, lockWait, lockTimeout;

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
                .build();
        this.localHit = Counter.builder("hub.cache").tag("result", "local_hit").register(meters);
        this.redisHit = Counter.builder("hub.cache").tag("result", "redis_hit").register(meters);
        this.miss = Counter.builder("hub.cache").tag("result", "miss").register(meters);
        this.dbLoad = Counter.builder("hub.cache.db_load").register(meters);
        this.lockWait = Counter.builder("hub.cache.lock_wait").register(meters);
        this.lockTimeout = Counter.builder("hub.cache.lock_timeout").register(meters);
    }

    @PreDestroy
    void shutdown() {
        rebuildPool.shutdownNow();
        try {
            if (!rebuildPool.awaitTermination(shutdownWait.toMillis(), TimeUnit.MILLISECONDS)) {
                log.warn("cache rebuild pool did not stop within {}ms", shutdownWait.toMillis());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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
        Page hit = freshPage(redis.opsForValue().get(key));
        if (hit != null) {
            redisHit.increment();
            return hit;
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
            Page hit = freshPage(redis.opsForValue().get(key));
            if (hit != null) {
                redisHit.increment();
                return hit;
            }
            String token = mutex.tryLock(lockKey, Duration.ofMillis(toggles.mutexLockTtlMs));
            if (token != null) {
                try {
                    Page again = freshPage(redis.opsForValue().get(key)); // double check
                    if (again != null) {
                        redisHit.increment();
                        return again;
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
        // 等了 2 秒还没等到，说明重建异常慢（数据库已经在吃力）。这时再回源只会雪上加霜，
        // 默认直接返回 503 让客户端稍后重试；DB 是修复前的行为，留着给复盘 03 复现。
        lockTimeout.increment();
        if (toggles.mutexFallback == Toggles.MutexFallback.DB) {
            log.warn("mutex wait timeout, load directly key={}", key);
            return load(loader);
        }
        throw new CacheBusyException(key);
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
        Stored stored = interpret(raw);
        if (stored.logical() && stored.expired()) {
            String lockKey = "lock:" + key;
            String token = mutex.tryLock(lockKey, Duration.ofMillis(toggles.mutexLockTtlMs));
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
        return stored.page();
    }

    private Page withMutexLogicalInit(String key, Supplier<Page> loader) {
        String lockKey = "lock:" + key;
        for (int i = 0; i < 100; i++) {
            String token = mutex.tryLock(lockKey, Duration.ofMillis(toggles.mutexLockTtlMs));
            if (token != null) {
                try {
                    String again = redis.opsForValue().get(key);
                    if (again != null) return interpret(again).page();
                    Page p = load(loader);
                    writeLogical(key, p);
                    return p;
                } finally {
                    mutex.unlock(lockKey, token);
                }
            }
            sleep(20);
            String raw = redis.opsForValue().get(key);
            if (raw != null) return interpret(raw).page();
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
        int ttl = ttlSeconds();
        ObjectNode n = om.createObjectNode();
        n.put("e", System.currentTimeMillis() + ttl * 1000L);
        n.set("d", om.valueToTree(p));
        // 物理 TTL 设成逻辑 TTL 的 10 倍，长期不访问的 key 仍会被回收
        redis.opsForValue().set(key, n.toString(), Duration.ofSeconds(ttl * 10L));
    }

    /** 基础 TTL + 随机抖动，避免同一批写入的 key 在同一秒过期（雪崩）。 */
    int ttlSeconds() {
        int jitter = toggles.ttlJitterSeconds;
        return toggles.cacheTtlSeconds + (jitter > 0 ? ThreadLocalRandom.current().nextInt(jitter + 1) : 0);
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

    /**
     * NONE / MUTEX 只接受还没过期的值。逻辑包装过期后当作未命中，马上按当前模式重建。
     * 未过期的包装、普通页、空标记都能读。
     */
    private Page freshPage(String raw) {
        if (raw == null) return null;
        Stored stored = interpret(raw);
        if (stored.logical() && stored.expired()) return null;
        return stored.page();
    }

    /** 普通 JSON、空标记、逻辑包装（含已过期和空页）都解成带 items 的 Page。 */
    private Stored interpret(String raw) {
        if (NULL_MARK.equals(raw)) return new Stored(emptyPage(), false, false);
        JsonNode wrapper = logicalWrapper(raw);
        if (wrapper != null) {
            long expireAt = wrapper.get("e").asLong();
            return new Stored(pageFromNode(wrapper.get("d")), true, System.currentTimeMillis() > expireAt);
        }
        return new Stored(readPage(raw), false, false);
    }

    /** 根上同时有 e、d，且没有 items，才是逻辑过期包装。普通 Page 的根上有 items。 */
    private JsonNode logicalWrapper(String raw) {
        if (raw == null || !raw.startsWith("{")) return null;
        JsonNode node = readTree(raw);
        if (node.has("e") && node.has("d") && !node.has("items")) return node;
        return null;
    }

    private Page pageFromNode(JsonNode data) {
        if (data == null || data.isNull()) return emptyPage();
        if (data.isTextual() && NULL_MARK.equals(data.asText())) return emptyPage();
        Page p = om.convertValue(data, Page.class);
        if (p == null || p.items() == null) return new Page(List.of(), p == null ? null : p.nextCursor(), p == null ? null : p.summary());
        return p;
    }

    private Page readPage(String raw) {
        try {
            Page p = om.readValue(raw, Page.class);
            if (p == null || p.items() == null) return emptyPage();
            return p;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Page emptyPage() {
        return new Page(List.of(), null, null);
    }

    private record Stored(Page page, boolean logical, boolean expired) {}

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

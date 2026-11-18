package io.commenthub.comment.cache;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/** SET NX PX 加锁，Lua 比较 token 再删，避免误删别人的锁。 */
@Component
public class RedisMutex {
    private static final DefaultRedisScript<Long> UNLOCK = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);
    private final StringRedisTemplate redis;

    public RedisMutex(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** 加锁成功返回 token，失败返回 null。 */
    public String tryLock(String key, Duration lease) {
        String token = UUID.randomUUID().toString();
        Boolean ok = redis.opsForValue().setIfAbsent(key, token, lease);
        return Boolean.TRUE.equals(ok) ? token : null;
    }

    public void unlock(String key, String token) {
        redis.execute(UNLOCK, List.of(key), token);
    }
}

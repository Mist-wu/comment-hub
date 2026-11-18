package io.commenthub.ai.guard;

import java.util.function.LongSupplier;

/**
 * 令牌桶。容量 capacity，每秒补充 refillPerSecond 个令牌。
 * 同一个类同时用于两种限流：
 *   - QPS：capacity = 突发量，refill = 每秒请求数；每次请求取 1
 *   - 每分钟 token 预算：capacity = 每分钟 token 数，refill = capacity / 60；每次请求取预估 token 数
 */
public final class TokenBucket {
    private final double capacity;
    private final double refillPerNano;
    private final LongSupplier clock;
    private double tokens;
    private long last;

    public TokenBucket(double capacity, double refillPerSecond) {
        this(capacity, refillPerSecond, System::nanoTime);
    }

    TokenBucket(double capacity, double refillPerSecond, LongSupplier clock) {
        this.capacity = capacity;
        this.refillPerNano = refillPerSecond / 1e9;
        this.clock = clock;
        this.tokens = capacity;
        this.last = clock.getAsLong();
    }

    public synchronized boolean tryAcquire(double n) {
        refill();
        if (tokens >= n) {
            tokens -= n;
            return true;
        }
        return false;
    }

    /** 实际用量超出预估时补扣，可以扣成负数，下一段时间自然少放行。 */
    public synchronized void forceTake(double n) {
        refill();
        tokens -= n;
    }

    public synchronized double available() {
        refill();
        return tokens;
    }

    private void refill() {
        long now = clock.getAsLong();
        tokens = Math.min(capacity, tokens + (now - last) * refillPerNano);
        last = now;
    }
}

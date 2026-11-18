package io.commenthub.ai.guard;

import io.commenthub.ai.GatewayToggles;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 所有模型调用的统一入口，按顺序过四道关：
 * <ol>
 *   <li>限流：QPS 令牌桶 + 每分钟 token 预算令牌桶，任一不够就降级（控成本）</li>
 *   <li>熔断：Resilience4j，错误率或慢调用比例超过阈值打开；打开 waitOpen 后半开，放少量请求试探</li>
 *   <li>舱壁：信号量限制同时在途的模型调用数，等不到许可立即降级，不排队</li>
 *   <li>超时：HTTP 超时取 min(配置超时, 调用方 gRPC deadline 剩余时间)</li>
 * </ol>
 * 调用方拿到降级结果后自己决定兜底（审核走关键词，摘要不显示）。
 */
@Component
public class GuardedLlm {
    private static final Logger log = LoggerFactory.getLogger(GuardedLlm.class);

    @FunctionalInterface
    public interface Attempt<T> {
        /** 返回 null 表示模型输出无法解析（已在内部重试过一次） */
        T run(Duration timeout) throws Exception;
    }

    private final GatewayToggles toggles;
    private final MeterRegistry meters;
    private final TokenBucket qps;
    private final TokenBucket tokenBudget;
    private final CircuitBreaker breaker;
    private volatile Semaphore bulkhead;
    private volatile int bulkheadSize;

    public GuardedLlm(GatewayToggles toggles, MeterRegistry meters,
                      @Value("${hub.limit.qps:50}") double qpsLimit,
                      @Value("${hub.limit.burst:100}") double burst,
                      @Value("${hub.limit.tokens-per-minute:200000}") double tokensPerMinute,
                      @Value("${hub.breaker.failure-rate:50}") float failureRate,
                      @Value("${hub.breaker.slow-rate:50}") float slowRate,
                      @Value("${hub.breaker.slow-ms:2000}") long slowMs,
                      @Value("${hub.breaker.open-ms:10000}") long openMs) {
        this.toggles = toggles;
        this.meters = meters;
        this.qps = new TokenBucket(burst, qpsLimit);
        this.tokenBudget = new TokenBucket(tokensPerMinute, tokensPerMinute / 60.0);
        CircuitBreakerConfig cfg = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(20)
                .minimumNumberOfCalls(10)
                .failureRateThreshold(failureRate)
                .slowCallRateThreshold(slowRate)
                .slowCallDurationThreshold(Duration.ofMillis(slowMs))
                .waitDurationInOpenState(Duration.ofMillis(openMs))
                .permittedNumberOfCallsInHalfOpenState(5)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .build();
        CircuitBreakerRegistry reg = CircuitBreakerRegistry.of(cfg);
        this.breaker = reg.circuitBreaker("llm");
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(reg).bindTo(meters);
        breaker.getEventPublisher().onStateTransition(e ->
                log.warn("circuit breaker {} -> {}", e.getStateTransition().getFromState(), e.getStateTransition().getToState()));
        this.bulkhead = new Semaphore(toggles.bulkheadMaxConcurrent);
        this.bulkheadSize = toggles.bulkheadMaxConcurrent;
        meters.gauge("hub.ai.bulkhead.available", this, g -> g.bulkhead.availablePermits());
        meters.gauge("hub.ai.token_budget.available", tokenBudget, TokenBucket::available);
    }

    public CircuitBreaker breaker() {
        return breaker;
    }

    /**
     * @param estTokens          预估 token（输入 + max_tokens），用于预算限流
     * @param callerRemainingMs  调用方 deadline 剩余毫秒，null 表示调用方没有 deadline
     */
    public <T> Outcome<T> call(String task, int estTokens, Long callerRemainingMs, Attempt<T> attempt) {
        if (toggles.rateLimitEnabled && (!qps.tryAcquire(1) || !tokenBudget.tryAcquire(estTokens))) {
            return degrade(task, "rate_limited");
        }
        boolean useBreaker = toggles.circuitBreakerEnabled;
        if (useBreaker && !breaker.tryAcquirePermission()) {
            return degrade(task, "circuit_open");
        }
        Semaphore sem = null;
        if (toggles.bulkheadEnabled) {
            sem = currentBulkhead();
            boolean got;
            try {
                got = sem.tryAcquire(toggles.bulkheadWaitMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                got = false;
            }
            if (!got) {
                if (useBreaker) breaker.releasePermission();
                return degrade(task, "bulkhead_full");
            }
        }
        long t0 = System.nanoTime();
        try {
            T v = attempt.run(timeout(callerRemainingMs));
            if (useBreaker) breaker.onSuccess(System.nanoTime() - t0, TimeUnit.NANOSECONDS);
            if (v == null) return degrade(task, "parse_error");
            meters.counter("hub.ai.calls", "task", task, "outcome", "ok").increment();
            return Outcome.ok(v);
        } catch (Exception e) {
            if (useBreaker) breaker.onError(System.nanoTime() - t0, TimeUnit.NANOSECONDS, e);
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return degrade(task, e instanceof HttpTimeoutException ? "timeout" : "upstream_error");
        } finally {
            if (sem != null) sem.release();
        }
    }

    /** 实际 token 超过预估时补扣预算。 */
    public void charge(int extraTokens) {
        if (extraTokens > 0) tokenBudget.forceTake(extraTokens);
    }

    private Duration timeout(Long callerRemainingMs) {
        int cfg = toggles.llmTimeoutMs;
        if (cfg <= 0 && callerRemainingMs == null) return null; // 不设超时：复盘 02 的事故配置
        long ms = cfg <= 0 ? callerRemainingMs : (callerRemainingMs == null ? cfg : Math.min(cfg, callerRemainingMs));
        return Duration.ofMillis(Math.max(ms, 1));
    }

    private Semaphore currentBulkhead() {
        int want = toggles.bulkheadMaxConcurrent;
        if (want != bulkheadSize) {
            synchronized (this) {
                if (want != bulkheadSize) {
                    bulkhead = new Semaphore(want);
                    bulkheadSize = want;
                }
            }
        }
        return bulkhead;
    }

    private <T> Outcome<T> degrade(String task, String reason) {
        meters.counter("hub.ai.calls", "task", task, "outcome", reason).increment();
        return Outcome.degraded(reason);
    }
}

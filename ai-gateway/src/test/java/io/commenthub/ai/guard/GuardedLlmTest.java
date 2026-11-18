package io.commenthub.ai.guard;

import io.commenthub.ai.GatewayToggles;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class GuardedLlmTest {
    GatewayToggles toggles = new GatewayToggles();

    GuardedLlm guard(double qps, double tokensPerMinute) {
        return new GuardedLlm(toggles, new SimpleMeterRegistry(), qps, qps, tokensPerMinute, 50, 50, 2000, 60_000);
    }

    @Test
    void rateLimitDegradesWhenQpsOrTokenBudgetExhausted() {
        GuardedLlm g = guard(2, 1000);
        assertThat(g.call("t", 10, null, t -> "a").isOk()).isTrue();
        assertThat(g.call("t", 10, null, t -> "a").isOk()).isTrue();
        assertThat(g.call("t", 10, null, t -> "a").degradeReason()).isEqualTo("rate_limited");

        GuardedLlm budget = guard(1000, 100);
        assertThat(budget.call("t", 80, null, t -> "a").isOk()).isTrue();
        assertThat(budget.call("t", 80, null, t -> "a").degradeReason()).isEqualTo("rate_limited");
    }

    @Test
    void breakerOpensAfterHalfOfCallsFail() {
        GuardedLlm g = guard(1000, 1e9);
        for (int i = 0; i < 10; i++) {
            int n = i;
            g.call("t", 1, null, t -> {
                if (n % 2 == 0) throw new IOException("boom");
                return "ok";
            });
        }
        assertThat(g.breaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(g.call("t", 1, null, t -> "ok").degradeReason()).isEqualTo("circuit_open");
    }

    @Test
    void timeoutAndParseErrorAreReportedSeparately() {
        GuardedLlm g = guard(1000, 1e9);
        assertThat(g.call("t", 1, null, t -> { throw new HttpTimeoutException("slow"); }).degradeReason()).isEqualTo("timeout");
        assertThat(g.call("t", 1, null, t -> null).degradeReason()).isEqualTo("parse_error");
    }

    @Test
    void timeoutNeverExceedsCallerDeadline() {
        GuardedLlm g = guard(1000, 1e9);
        toggles.llmTimeoutMs = 3000;
        assertThat(g.call("t", 1, 150L, t -> t.toMillis()).value()).isEqualTo(150L);
        assertThat(g.call("t", 1, null, t -> t.toMillis()).value()).isEqualTo(3000L);
        toggles.llmTimeoutMs = 0;
        assertThat(g.call("t", 1, null, t -> t == null ? "none" : "set").value()).isEqualTo("none");
    }

    @Test
    void bulkheadRejectsInsteadOfQueueing() throws Exception {
        toggles.bulkheadMaxConcurrent = 1;
        toggles.bulkheadWaitMs = 0;
        GuardedLlm g = guard(1000, 1e9);
        CountDownLatch inside = new CountDownLatch(1), release = new CountDownLatch(1);
        Thread t = Thread.ofVirtual().start(() -> g.call("t", 1, null, x -> {
            inside.countDown();
            release.await();
            return "slow";
        }));
        assertThat(inside.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(g.call("t", 1, null, x -> "fast").degradeReason()).isEqualTo("bulkhead_full");
        release.countDown();
        t.join();
        assertThat(g.call("t", 1, null, x -> "fast").isOk()).isTrue();
    }
}

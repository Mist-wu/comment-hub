package io.commenthub.ai.guard;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class TokenBucketTest {
    @Test
    void burstThenRefillOverTime() {
        AtomicLong now = new AtomicLong();
        TokenBucket b = new TokenBucket(2, 1, now::get);
        assertThat(b.tryAcquire(1)).isTrue();
        assertThat(b.tryAcquire(1)).isTrue();
        assertThat(b.tryAcquire(1)).isFalse();
        now.addAndGet(1_000_000_000L); // 1 秒后补 1 个
        assertThat(b.tryAcquire(1)).isTrue();
        assertThat(b.tryAcquire(1)).isFalse();
    }

    @Test
    void tokenBudgetRejectsLargeRequestAndForceTakeCanGoNegative() {
        AtomicLong now = new AtomicLong();
        TokenBucket budget = new TokenBucket(600, 10, now::get); // 每分钟 600 token
        assertThat(budget.tryAcquire(500)).isTrue();
        assertThat(budget.tryAcquire(200)).isFalse();
        budget.forceTake(300);
        assertThat(budget.available()).isEqualTo(-200);
        now.addAndGet(30_000_000_000L); // 30 秒补 300
        assertThat(budget.available()).isEqualTo(100);
    }
}

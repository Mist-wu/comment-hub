package io.commenthub.consumer;

import org.springframework.stereotype.Component;

@Component
public class ConsumerToggles {
    /** 审核消费者人为失败的比例，用来演示重试 → 死信 → 重放 */
    public volatile double moderationFailRate = 0.0;
    /** 审核调用 ai-gateway 的 deadline */
    public volatile int moderationDeadlineMs = 5000;
}

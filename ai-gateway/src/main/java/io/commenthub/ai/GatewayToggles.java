package io.commenthub.ai;

import org.springframework.stereotype.Component;

/** 运行时开关，POST /admin/toggles 修改。默认值是“优化版”。 */
@Component
public class GatewayToggles {
    /** 调模型的 HTTP 超时，0 = 不设超时（复盘 02 用） */
    public volatile int llmTimeoutMs = 3000;
    /** 舱壁：模型调用最大并发；关掉后不限并发 */
    public volatile boolean bulkheadEnabled = true;
    public volatile int bulkheadMaxConcurrent = 32;
    public volatile int bulkheadWaitMs = 20;
    /** 摘要未命中时异步生成；关掉后在请求线程里同步等模型（复盘 02 用） */
    public volatile boolean summaryAsync = true;
    public volatile boolean rateLimitEnabled = true;
    public volatile boolean circuitBreakerEnabled = true;
}

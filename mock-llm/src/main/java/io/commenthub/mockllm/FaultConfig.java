package io.commenthub.mockllm;

import org.springframework.stereotype.Component;

/** 故障注入参数，运行时通过 POST /admin/config 修改。 */
@Component
public class FaultConfig {
    /** 基础延迟 */
    public volatile int latencyMs = 300;
    /** 在基础延迟上加 0~jitterMs 的随机值 */
    public volatile int jitterMs = 200;
    /** 返回 HTTP 500 的比例 */
    public volatile double errorRate = 0.0;
    /** 卡住 hangMs 不返回的比例，模拟上游超时 */
    public volatile double timeoutRate = 0.0;
    public volatile int hangMs = 120_000;
    /** 返回内容不是合法 JSON 的比例，用来测解析失败重试 */
    public volatile double badJsonRate = 0.0;
}

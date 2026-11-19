package io.commenthub.ai.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 模型服务配置。provider=mock 时 baseUrl 指向 mock-llm；provider=deepseek 时指向 DeepSeek。
 * disableThinking：DeepSeek 的 flash 模型默认先输出思考过程，思考 token 也计入 max_tokens，
 * 审核这种短 JSON 任务会被截断（见 docs/cost.md），所以评估时关掉。
 * 单价单位：元 / 百万 token。未配置（null）时不计算费用，和 y2b-rs 一样不伪造账单。
 */
@ConfigurationProperties(prefix = "hub.llm")
public record LlmProperties(
        String provider,
        String baseUrl,
        String apiKey,
        String model,
        Double priceInputPerM,
        Double priceCacheHitPerM,
        Double priceOutputPerM,
        Boolean disableThinking) {
}

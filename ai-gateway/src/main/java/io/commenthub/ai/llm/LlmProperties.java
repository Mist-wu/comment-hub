package io.commenthub.ai.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 模型服务配置。provider=mock 时 baseUrl 指向 mock-llm；provider=deepseek 时指向 DeepSeek。
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
        Double priceOutputPerM) {
}

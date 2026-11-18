package io.commenthub.ai.llm;

import com.fasterxml.jackson.databind.JsonNode;

/** 输入 token 拆成“未命中缓存”和“命中缓存”两部分，因为两者单价不同。 */
public record LlmUsage(int input, int cacheRead, int output, int total) {
    public static final LlmUsage ZERO = new LlmUsage(0, 0, 0, 0);

    public static LlmUsage parse(JsonNode u) {
        int prompt = Math.max(0, u.path("prompt_tokens").asInt(0));
        int cache = u.has("prompt_cache_hit_tokens") ? u.path("prompt_cache_hit_tokens").asInt(0)
                : u.path("prompt_tokens_details").path("cached_tokens").asInt(0);
        cache = Math.min(Math.max(cache, 0), prompt);
        int out = Math.max(0, u.path("completion_tokens").asInt(0));
        int total = u.path("total_tokens").asInt(prompt + out);
        return new LlmUsage(prompt - cache, cache, out, total);
    }

    /** 费用，单位百万分之一元。单价未配置返回 null。 */
    public Long costMicroCny(LlmProperties p) {
        if (p.priceInputPerM() == null || p.priceOutputPerM() == null) return null;
        double cachePrice = p.priceCacheHitPerM() == null ? p.priceInputPerM() : p.priceCacheHitPerM();
        // 元/百万token × token 数 = 百万分之一元
        return Math.round(input * p.priceInputPerM() + cacheRead * cachePrice + output * p.priceOutputPerM());
    }
}

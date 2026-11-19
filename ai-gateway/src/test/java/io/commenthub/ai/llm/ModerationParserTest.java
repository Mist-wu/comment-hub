package io.commenthub.ai.llm;

import io.commenthub.ai.v1.Label;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ModerationParserTest {
    @Test
    void acceptsValidJsonAndFencedJson() {
        assertThat(ModerationParser.parse("{\"label\":\"REJECT\",\"reason\":\"辱骂\",\"confidence\":0.9}"))
                .hasValueSatisfying(v -> assertThat(v.label()).isEqualTo(Label.REJECT));
        assertThat(ModerationParser.parse("```json\n{\"label\":\"PASS\",\"reason\":\"ok\",\"confidence\":1}\n```")).isPresent();
    }

    @Test
    void rejectsSchemaViolations() {
        assertThat(ModerationParser.parse("好的，结果是 PASS")).isEmpty();
        assertThat(ModerationParser.parse("{\"label\":\"MAYBE\",\"reason\":\"x\",\"confidence\":0.5}")).isEmpty();
        assertThat(ModerationParser.parse("{\"label\":\"PASS\",\"reason\":\"x\",\"confidence\":1.5}")).isEmpty();
        assertThat(ModerationParser.parse("{\"label\":\"PASS\",\"reason\":\"\",\"confidence\":0.5}")).isEmpty();
        assertThat(ModerationParser.parse("{\"label\":\"PASS\",\"confidence\":0.5}")).isEmpty();
        assertThat(ModerationParser.parse("{\"label\":\"PASS\",\"reason\":\"x\",\"confidence\":\"high\"}")).isEmpty();
    }

    @Test
    void usageSplitsCacheHitAndComputesCost() {
        var om = new com.fasterxml.jackson.databind.ObjectMapper();
        LlmUsage u = LlmUsage.parse(om.valueToTree(java.util.Map.of(
                "prompt_tokens", 1000, "prompt_cache_hit_tokens", 400, "completion_tokens", 100, "total_tokens", 1100)));
        assertThat(u).isEqualTo(new LlmUsage(600, 400, 100, 1100));
        LlmProperties p = new LlmProperties("x", "u", "", "m", 2.0, 0.5, 8.0, null);
        // 600*2 + 400*0.5 + 100*8 = 2200 百万分之一元
        assertThat(u.costMicroCny(p)).isEqualTo(2200L);
        assertThat(u.costMicroCny(new LlmProperties("x", "u", "", "m", null, null, null, null))).isNull();
    }
}

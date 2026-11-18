package io.commenthub.mockllm;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FakeModelTest {
    @Test
    void moderationFollowsKeywords() {
        assertThat(FakeModel.moderate("你就是个骗子")).contains("REJECT");
        assertThat(FakeModel.moderate("兼职日结加微信")).contains("REVIEW");
        assertThat(FakeModel.moderate("拍得真好")).contains("PASS");
    }
}

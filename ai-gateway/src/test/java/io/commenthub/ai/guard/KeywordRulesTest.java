package io.commenthub.ai.guard;

import io.commenthub.ai.v1.Label;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KeywordRulesTest {
    @Test
    void rules() {
        assertThat(KeywordRules.judge("你是骗子吧").label()).isEqualTo(Label.REJECT);
        assertThat(KeywordRules.judge("兼职日结 加vx 13800000000").label()).isEqualTo(Label.REVIEW);
        assertThat(KeywordRules.judge("这期剪辑太好了").label()).isEqualTo(Label.PASS);
    }
}

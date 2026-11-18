package io.commenthub.comment.cache;

import io.commenthub.comment.config.Toggles;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HotKeyDetectorTest {
    @Test
    void keyBecomesHotOnlyAfterWindowRotatesAndCoolsDown() {
        Toggles t = new Toggles();
        t.hotKeyThreshold = 3;
        HotKeyDetector d = new HotKeyDetector(t);
        for (int i = 0; i < 3; i++) d.record("a");
        d.record("b");
        assertThat(d.isHot("a")).isFalse(); // 当前窗口还没结算
        d.rotate();
        assertThat(d.isHot("a")).isTrue();
        assertThat(d.isHot("b")).isFalse();
        d.rotate(); // 下一秒没有访问
        assertThat(d.isHot("a")).isFalse();
    }
}

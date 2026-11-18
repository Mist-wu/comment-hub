package io.commenthub.comment.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CursorTest {
    @Test
    void latestRoundTrip() {
        String c = Cursor.latest(1_700_000_000_123L, 42L);
        assertThat(Cursor.parseLatest(c)).isEqualTo(new Cursor.Latest(1_700_000_000_123L, 42L));
    }

    @Test
    void hotRoundTrip() {
        assertThat(Cursor.parseHot(Cursor.hot(40))).isEqualTo(40);
    }

    @Test
    void rejectsWrongKindAndGarbage() {
        assertThatThrownBy(() -> Cursor.parseLatest(Cursor.hot(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Cursor.parseHot("!!!")).isInstanceOf(IllegalArgumentException.class);
    }
}

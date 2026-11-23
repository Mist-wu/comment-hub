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
        Cursor.HotCursor legacy = Cursor.parseHotCursor(Cursor.hot(990));
        assertThat(legacy.kind()).isEqualTo(Cursor.HotKind.LEGACY);
        assertThat(legacy.offset()).isEqualTo(990);
        Cursor.HotCursor zset = Cursor.parseHotCursor(Cursor.hotZset(20, 7, 5, 100));
        assertThat(zset).isEqualTo(new Cursor.HotCursor(Cursor.HotKind.ZSET, 20, 7, 5, 100));
        Cursor.HotCursor db = Cursor.parseHotCursor(Cursor.hotDb(5, 100));
        assertThat(db.kind()).isEqualTo(Cursor.HotKind.DB);
        assertThat(db.likeCount()).isEqualTo(5);
        assertThat(db.id()).isEqualTo(100);
    }

    @Test
    void rejectsWrongKindAndGarbage() {
        assertThatThrownBy(() -> Cursor.parseLatest(Cursor.hot(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Cursor.parseHot("!!!")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Cursor.parseHot(Cursor.hot(-1))).isInstanceOf(IllegalArgumentException.class);
    }
}

package io.commenthub.consumer;

import io.commenthub.common.event.LikeChanged;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class LikeFlushMergeTest {
    static LikeChanged ev(String id, long comment, int delta) {
        return new LikeChanged(id, comment, 1, delta, 0);
    }

    @Test
    void sumsPerCommentAndIgnoresAlreadySeenAndInBatchDuplicates() {
        List<LikeChanged> evs = List.of(ev("a", 1, 1), ev("b", 1, 1), ev("c", 2, 1), ev("d", 1, -1),
                ev("a", 1, 1),   // 同一批里的重复（重放）
                ev("old", 2, 1)); // 之前批次已经处理过
        var deltas = LikeFlushListener.merge(evs, ids -> {
            Set<String> fresh = new java.util.LinkedHashSet<>(ids);
            fresh.remove("old");
            return fresh;
        });
        assertThat(deltas).containsEntry(1L, 1).containsEntry(2L, 1).hasSize(2);
    }

    @Test
    void replayingWholeBatchChangesNothing() {
        List<LikeChanged> evs = List.of(ev("a", 1, 1), ev("b", 1, 1));
        assertThat(LikeFlushListener.merge(evs, ids -> Set.of())).isEmpty();
    }
}

package io.commenthub.ai.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SummaryServiceTest {
    @Test
    void tierChangesOnlyAtPowersOfTen() {
        assertThat(SummaryService.tier(99)).isZero();
        assertThat(SummaryService.tier(100)).isEqualTo(1);
        assertThat(SummaryService.tier(999)).isEqualTo(1);
        assertThat(SummaryService.tier(1000)).isEqualTo(2);
        assertThat(SummaryService.tier(99_999)).isEqualTo(3);
        assertThat(SummaryService.tier(1_500_000)).isEqualTo(4);
        assertThat(SummaryService.key(7, 5000)).isEqualTo(SummaryService.key(7, 9000));
    }
}

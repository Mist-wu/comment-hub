package io.commenthub.datagen;

import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;

import static org.assertj.core.api.Assertions.assertThat;

class ZipfTest {
    @Test
    void topOnePercentGetsMostTraffic() {
        Zipf z = new Zipf(10_000, 1.1);
        assertThat(z.topShare(100)).isGreaterThan(0.6);
        SplittableRandom r = new SplittableRandom(1);
        int hits = 0, n = 200_000;
        for (int i = 0; i < n; i++) if (z.sample(r) <= 100) hits++;
        assertThat((double) hits / n).isCloseTo(z.topShare(100), org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    void textsAreTsvSafe() {
        SplittableRandom r = new SplittableRandom(2);
        for (int i = 0; i < 10_000; i++) {
            String t = Texts.comment(r);
            assertThat(t).isNotEmpty().doesNotContain("\t", "\n", "\\");
            assertThat(t.length()).isLessThanOrEqualTo(500);
        }
    }
}

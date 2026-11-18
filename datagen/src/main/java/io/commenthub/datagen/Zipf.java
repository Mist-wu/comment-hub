package io.commenthub.datagen;

import java.util.Arrays;
import java.util.random.RandomGenerator;

/**
 * Zipf 分布：第 r 名（从 1 开始）的权重 ∝ 1 / r^s。
 * 用累积分布 + 二分查找采样，1 万个元素时每次采样约 14 次比较。
 * k6 脚本里用同一个公式生成读流量，保证“数据热”和“流量热”是同一批视频。
 */
public final class Zipf {
    private final double[] cdf;

    public Zipf(int n, double s) {
        cdf = new double[n];
        double sum = 0;
        for (int r = 1; r <= n; r++) {
            sum += 1.0 / Math.pow(r, s);
            cdf[r - 1] = sum;
        }
        for (int i = 0; i < n; i++) cdf[i] /= sum;
    }

    /** 返回 1..n 的名次 */
    public int sample(RandomGenerator rnd) {
        int i = Arrays.binarySearch(cdf, rnd.nextDouble());
        return (i >= 0 ? i : -i - 1) + 1;
    }

    /** 第 r 名的概率 */
    public double p(int r) {
        return r == 1 ? cdf[0] : cdf[r - 1] - cdf[r - 2];
    }

    /** 前 k 名的概率之和 */
    public double topShare(int k) {
        return cdf[Math.min(k, cdf.length) - 1];
    }

    public int size() {
        return cdf.length;
    }
}

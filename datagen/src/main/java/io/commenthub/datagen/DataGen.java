package io.commenthub.datagen;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * 数据生成器。默认规模：1 万视频、1000 万评论、5000 万点赞、100 万用户。
 *
 * <ol>
 *   <li>每条评论按 Zipf(s) 选视频：视频 ID 就是热度名次，1 号最热。</li>
 *   <li>每个视频分到的点赞总数也按同一个 Zipf；视频内部每条评论再按 Zipf 分点赞，形成“高赞评论”。</li>
 *   <li>一条评论的 k 个点赞用户用 (start + j * stride) mod U 生成，保证互不相同，(user_id, comment_id) 唯一键不会冲突，
 *       因此 comment.like_count 与 comment_like 行数严格一致，对账有可靠的起点。</li>
 *   <li>写 TSV 到 MySQL 的 secure_file_priv 目录，再 LOAD DATA INFILE；导入前删二级索引，导入后再建，快很多。</li>
 * </ol>
 *
 * 用法：java -jar datagen.jar [--videos N] [--comments N] [--likes N] [--users N] [--zipf 1.1]
 *       [--dir /var/lib/mysql-files] [--jdbc URL] [--user root] [--password root] [--seed 42] [--gold 500] [--no-load]
 */
public final class DataGen {
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    /** 与 1_000_000 互质的素数步长 */
    private static final long STRIDE = 7919;

    public static void main(String[] argv) throws Exception {
        Map<String, String> a = parse(argv);
        int videos = Integer.parseInt(a.getOrDefault("videos", "10000"));
        int comments = Integer.parseInt(a.getOrDefault("comments", "10000000"));
        long likes = Long.parseLong(a.getOrDefault("likes", "50000000"));
        int users = Integer.parseInt(a.getOrDefault("users", "1000000"));
        double s = Double.parseDouble(a.getOrDefault("zipf", "1.1"));
        long seed = Long.parseLong(a.getOrDefault("seed", "42"));
        int gold = Integer.parseInt(a.getOrDefault("gold", "500"));
        Path dir = Path.of(a.getOrDefault("dir", "/var/lib/mysql-files"));
        Files.createDirectories(dir);

        long t0 = System.currentTimeMillis();
        Zipf vz = new Zipf(videos, s);
        System.out.printf("videos=%d comments=%d likes=%d users=%d zipf=%.2f%n", videos, comments, likes, users, s);
        System.out.printf("top 1%% videos share = %.1f%%, top 1 share = %.1f%%%n",
                100 * vz.topShare(Math.max(1, videos / 100)), 100 * vz.p(1));

        // ---- 第一遍：给每条评论分配视频、视频内名次 ----
        SplittableRandom rnd = new SplittableRandom(seed);
        int[] videoOf = new int[comments];
        int[] perVideo = new int[videos + 1];
        for (int i = 0; i < comments; i++) {
            int v = vz.sample(rnd);
            videoOf[i] = v;
            perVideo[v]++;
        }
        // 视频内名次：按出现顺序随机打散，用 [1, n_v] 均匀随机数近似一个排列
        int[] rankInVideo = new int[comments];
        for (int i = 0; i < comments; i++) rankInVideo[i] = 1 + rnd.nextInt(perVideo[videoOf[i]]);

        // 每个视频内部 Zipf 权重之和 H(n_v)，用于归一化
        double[] hv = new double[videos + 1];
        for (int v = 1; v <= videos; v++) {
            double h = 0;
            for (int r = 1; r <= perVideo[v]; r++) h += 1.0 / Math.pow(r, s);
            hv[v] = h;
        }

        // ---- 每条评论的点赞数 = 视频点赞总数 × 评论在视频内的 Zipf 概率 ----
        int[] likeOf = new int[comments];
        long totalLikes = 0;
        for (int i = 0; i < comments; i++) {
            int v = videoOf[i];
            double expected = likes * vz.p(v) * (1.0 / Math.pow(rankInVideo[i], s)) / hv[v];
            // 期望值取整：整数部分 + 小数部分按概率进位，总数的期望仍是 likes
            int k = (int) expected;
            if (rnd.nextDouble() < expected - k) k++;
            k = Math.min(k, users);
            likeOf[i] = k;
            totalLikes += k;
        }
        System.out.printf("plan done in %ds, actual likes=%d%n", (System.currentTimeMillis() - t0) / 1000, totalLikes);

        // ---- 写 TSV ----
        LocalDateTime start = LocalDateTime.of(2026, 8, 1, 0, 0);
        long spanMillis = 90L * 24 * 3600 * 1000;
        Path vf = dir.resolve("video.tsv"), cf = dir.resolve("comment.tsv"), lf = dir.resolve("comment_like.tsv"), gf = dir.resolve("gold.tsv");
        try (BufferedWriter w = writer(vf)) {
            for (int v = 1; v <= videos; v++) {
                w.write(v + "\t视频 #" + v + "\t" + perVideo[v] + "\t" + TS.format(start) + "\n");
            }
        }
        int goldEvery = gold > 0 ? Math.max(1, comments / gold) : Integer.MAX_VALUE;
        try (BufferedWriter cw = writer(cf); BufferedWriter lw = writer(lf); BufferedWriter gw = writer(gf)) {
            int goldWritten = 0;
            for (int i = 0; i < comments; i++) {
                long id = i + 1L;
                // ID 和时间同序递增，近似真实的自增主键
                LocalDateTime ts = start.plusNanos((spanMillis * i / comments) * 1_000_000L);
                String text = Texts.comment(rnd);
                cw.write(id + "\t" + videoOf[i] + "\t" + (1 + rnd.nextInt(users)) + "\t" + text + "\t" + likeOf[i] + "\t" + Texts.status(text) + "\t" + TS.format(ts) + "\n");
                int k = likeOf[i];
                if (k > 0) {
                    long base = rnd.nextInt(users);
                    String lts = TS.format(ts.plusMinutes(1));
                    for (int j = 0; j < k; j++) {
                        long u = (base + j * STRIDE) % users + 1;
                        lw.write(u + "\t" + id + "\t" + lts + "\n");
                    }
                }
                if (i % goldEvery == 0 && goldWritten < gold) {
                    gw.write(text + "\n");
                    goldWritten++;
                }
                if ((i + 1) % 1_000_000 == 0) {
                    System.out.printf("written %d comments (%ds)%n", i + 1, (System.currentTimeMillis() - t0) / 1000);
                }
            }
        }
        System.out.printf("tsv done in %ds%n", (System.currentTimeMillis() - t0) / 1000);

        if (a.containsKey("no-load")) return;
        String url = a.getOrDefault("jdbc", "jdbc:mysql://localhost:3306/comment_hub?useSSL=false&allowPublicKeyRetrieval=true");
        try (Connection c = DriverManager.getConnection(url, a.getOrDefault("user", "root"), a.getOrDefault("password", "root"));
             Statement st = c.createStatement()) {
            exec(st, "SET SESSION unique_checks = 0");
            exec(st, "SET SESSION foreign_key_checks = 0");
            for (String t : new String[]{"video", "comment", "comment_like", "consumed_event", "event_outbox", "moderation_gold"}) {
                exec(st, "TRUNCATE TABLE " + t);
            }
            dropIndex(st, "comment", "idx_video_created");
            dropIndex(st, "comment", "idx_video_like");
            dropIndex(st, "comment_like", "uk_user_comment");
            dropIndex(st, "comment_like", "idx_comment");
            load(st, vf, "video", "(id, title, comment_count, created_at)");
            load(st, cf, "comment", "(id, video_id, user_id, content, like_count, status, created_at)");
            load(st, lf, "comment_like", "(user_id, comment_id, created_at)");
            load(st, gf, "moderation_gold", "(content)");
            timed(st, "ALTER TABLE comment ADD KEY idx_video_created (video_id, created_at, id), ADD KEY idx_video_like (video_id, like_count, id, status)");
            timed(st, "ALTER TABLE comment_like ADD UNIQUE KEY uk_user_comment (user_id, comment_id), ADD KEY idx_comment (comment_id)");
            timed(st, "ANALYZE TABLE video, comment, comment_like");
        }
        System.out.printf("all done in %ds%n", (System.currentTimeMillis() - t0) / 1000);
    }

    private static void load(Statement st, Path f, String table, String cols) throws SQLException {
        timed(st, "LOAD DATA INFILE '" + f + "' INTO TABLE " + table
                + " CHARACTER SET utf8mb4 FIELDS TERMINATED BY '\\t' LINES TERMINATED BY '\\n' " + cols);
    }

    private static void dropIndex(Statement st, String table, String idx) {
        try {
            st.execute("ALTER TABLE " + table + " DROP INDEX " + idx);
        } catch (SQLException ignored) {
            // 已经不存在
        }
    }

    private static void timed(Statement st, String sql) throws SQLException {
        long t = System.currentTimeMillis();
        st.execute(sql);
        System.out.printf("%6.1fs  %s%n", (System.currentTimeMillis() - t) / 1000.0, sql.length() > 120 ? sql.substring(0, 120) + "…" : sql);
    }

    private static void exec(Statement st, String sql) throws SQLException {
        st.execute(sql);
    }

    private static BufferedWriter writer(Path p) throws IOException {
        return Files.newBufferedWriter(p, StandardCharsets.UTF_8);
    }

    private static Map<String, String> parse(String[] argv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < argv.length; i++) {
            if (!argv[i].startsWith("--")) continue;
            String k = argv[i].substring(2);
            if (i + 1 < argv.length && !argv[i + 1].startsWith("--")) m.put(k, argv[++i]);
            else m.put(k, "true");
        }
        return m;
    }
}

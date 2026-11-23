package io.commenthub.comment.service;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 游标对客户端不透明。
 * 最新排序：编码最后一条的 (createdAtMillis, id)；最热排序：编码 ZSet 里的下一个名次。
 */
public final class Cursor {
    private Cursor() {}

    public record Latest(long createdAtMillis, long id) {}

    public static String latest(long createdAtMillis, long id) {
        return enc("L:" + createdAtMillis + ":" + id);
    }

    public static String hot(long rank) {
        return enc("H:" + rank);
    }

    /** 榜单内的下一页：带上这一版榜单的代号，以及本页最后一条的点赞数和 id。 */
    public static String hotZset(long offset, long generation, long likeCount, long id) {
        return enc("HZ:" + offset + ":" + generation + ":" + likeCount + ":" + id);
    }

    /** 榜单外或榜单换代之后，按点赞数和 id 在库里接着翻。 */
    public static String hotDb(long likeCount, long id) {
        return enc("HD:" + likeCount + ":" + id);
    }

    public enum HotKind { LEGACY, ZSET, DB }

    /** legacy 的 H:offset 没有 generation / likeCount / id，这三个字段是 -1。 */
    public record HotCursor(HotKind kind, long offset, long generation, long likeCount, long id) {}

    public static HotCursor parseHotCursor(String c) {
        String[] p = dec(c).split(":");
        try {
            if (p.length == 2 && p[0].equals("H")) {
                long rank = Long.parseLong(p[1]);
                if (rank < 0) throw new IllegalArgumentException("bad cursor");
                return new HotCursor(HotKind.LEGACY, rank, -1, -1, -1);
            }
            if (p.length == 5 && p[0].equals("HZ")) {
                long offset = Long.parseLong(p[1]);
                long generation = Long.parseLong(p[2]);
                long like = Long.parseLong(p[3]);
                long id = Long.parseLong(p[4]);
                if (offset < 0 || like < 0 || id < 0) throw new IllegalArgumentException("bad cursor");
                return new HotCursor(HotKind.ZSET, offset, generation, like, id);
            }
            if (p.length == 3 && p[0].equals("HD")) {
                long like = Long.parseLong(p[1]);
                long id = Long.parseLong(p[2]);
                if (like < 0 || id < 0) throw new IllegalArgumentException("bad cursor");
                return new HotCursor(HotKind.DB, 0, -1, like, id);
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("bad cursor", e);
        }
        throw new IllegalArgumentException("bad cursor");
    }

    public static Latest parseLatest(String c) {
        String[] p = dec(c).split(":");
        if (p.length != 3 || !p[0].equals("L")) throw new IllegalArgumentException("bad cursor");
        return new Latest(Long.parseLong(p[1]), Long.parseLong(p[2]));
    }

    public static long parseHot(String c) {
        String[] p = dec(c).split(":");
        if (p.length != 2 || !p[0].equals("H")) throw new IllegalArgumentException("bad cursor");
        long rank = Long.parseLong(p[1]);
        if (rank < 0) throw new IllegalArgumentException("bad cursor");
        return rank;
    }

    private static String enc(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String dec(String s) {
        try {
            return new String(Base64.getUrlDecoder().decode(s), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("bad cursor", e);
        }
    }
}

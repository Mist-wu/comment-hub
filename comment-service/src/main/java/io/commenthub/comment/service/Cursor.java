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

    public static Latest parseLatest(String c) {
        String[] p = dec(c).split(":");
        if (p.length != 3 || !p[0].equals("L")) throw new IllegalArgumentException("bad cursor");
        return new Latest(Long.parseLong(p[1]), Long.parseLong(p[2]));
    }

    public static long parseHot(String c) {
        String[] p = dec(c).split(":");
        if (p.length != 2 || !p[0].equals("H")) throw new IllegalArgumentException("bad cursor");
        return Long.parseLong(p[1]);
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

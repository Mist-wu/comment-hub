package io.commenthub.mockllm;

import java.util.List;
import java.util.regex.Pattern;

/** 用关键词规则伪造模型输出：审核返回 JSON，摘要返回一段文字。 */
final class FakeModel {
    private static final Pattern REJECT = Pattern.compile("傻[逼比]|垃圾|滚|去死|骗子|诈骗");
    private static final Pattern REVIEW = Pattern.compile("加微信|vx|VX|私信我|兼职|返利|http");

    private FakeModel() {}

    static String moderate(String comment) {
        if (REJECT.matcher(comment).find()) {
            return "{\"label\":\"REJECT\",\"reason\":\"包含辱骂或欺诈词\",\"confidence\":0.93}";
        }
        if (REVIEW.matcher(comment).find()) {
            return "{\"label\":\"REVIEW\",\"reason\":\"疑似引流广告\",\"confidence\":0.71}";
        }
        return "{\"label\":\"PASS\",\"reason\":\"正常评论\",\"confidence\":0.97}";
    }

    static String summarize(List<String> comments) {
        int n = comments.size();
        String first = n == 0 ? "" : comments.get(0);
        if (first.length() > 20) first = first.substring(0, 20);
        return "评论区共采样 " + n + " 条，整体氛围积极，大家主要在讨论视频内容本身；高赞观点如：“" + first + "”。";
    }

    /** 粗略估算 token：中文约 1.5 字 / token。 */
    static int tokens(String s) {
        return Math.max(1, (int) Math.ceil(s.length() / 1.5));
    }
}

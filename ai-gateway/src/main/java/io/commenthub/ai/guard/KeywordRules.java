package io.commenthub.ai.guard;

import io.commenthub.ai.v1.Label;
import io.commenthub.ai.llm.ModerationParser.Verdict;

import java.util.regex.Pattern;

/**
 * 审核降级：模型不可用时用关键词规则兜底。宁可多送人工复核（REVIEW），也不放过明显违规。
 * 置信度固定为较低值，方便事后把规则判的结果再送模型复审。
 */
public final class KeywordRules {
    private static final Pattern REJECT = Pattern.compile("傻[逼比bB]|sb|SB|垃圾人|去死|滚出|骗子|诈骗|操你");
    private static final Pattern REVIEW = Pattern.compile("加微信|加v|vx|VX|威信|私信我|兼职|日结|返利|代刷|http|www\\.|\\d{6,}");

    private KeywordRules() {}

    public static Verdict judge(String content) {
        if (REJECT.matcher(content).find()) return new Verdict(Label.REJECT, "规则命中：辱骂/欺诈词", 0.6);
        if (REVIEW.matcher(content).find()) return new Verdict(Label.REVIEW, "规则命中：疑似引流", 0.5);
        return new Verdict(Label.PASS, "规则未命中", 0.4);
    }
}

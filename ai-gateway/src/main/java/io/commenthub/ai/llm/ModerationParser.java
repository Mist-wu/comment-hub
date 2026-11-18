package io.commenthub.ai.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.commenthub.ai.v1.Label;

import java.util.Optional;

/**
 * 按 schema 校验模型输出：label ∈ {PASS, REVIEW, REJECT}，reason 为非空字符串，confidence ∈ [0, 1]。
 * 允许模型在 JSON 外面包一层 ```json 代码块；其余情况一律算解析失败。
 */
public final class ModerationParser {
    public record Verdict(Label label, String reason, double confidence) {}

    private static final ObjectMapper OM = new ObjectMapper();

    private ModerationParser() {}

    public static Optional<Verdict> parse(String raw) {
        if (raw == null) return Optional.empty();
        String s = raw.strip();
        if (s.startsWith("```")) {
            int a = s.indexOf('{');
            int b = s.lastIndexOf('}');
            if (a < 0 || b < a) return Optional.empty();
            s = s.substring(a, b + 1);
        }
        try {
            JsonNode n = OM.readTree(s);
            if (n == null || !n.isObject()) return Optional.empty();
            JsonNode l = n.get("label"), r = n.get("reason"), c = n.get("confidence");
            if (l == null || !l.isTextual() || r == null || !r.isTextual() || c == null || !c.isNumber()) return Optional.empty();
            Label label = switch (l.asText()) {
                case "PASS" -> Label.PASS;
                case "REVIEW" -> Label.REVIEW;
                case "REJECT" -> Label.REJECT;
                default -> null;
            };
            double conf = c.asDouble();
            if (label == null || r.asText().isBlank() || conf < 0 || conf > 1) return Optional.empty();
            String reason = r.asText().length() > 60 ? r.asText().substring(0, 60) : r.asText();
            return Optional.of(new Verdict(label, reason, conf));
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}

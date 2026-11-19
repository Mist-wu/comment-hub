package io.commenthub.ai.service;

import io.commenthub.ai.guard.KeywordRules;
import io.commenthub.ai.llm.ModerationParser;
import io.commenthub.ai.llm.Prompts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;

/**
 * 审核质量评估：对 moderation_gold 里 human_label 非空的样本，逐条直接调模型（不走缓存、不走限流），
 * 同时算关键词规则的结果，输出两者的准确率和混淆矩阵。真模型只在这里调用。
 */
@Service
public class EvalService {
    private static final Logger log = LoggerFactory.getLogger(EvalService.class);
    private final JdbcTemplate jdbc;
    private final LlmInvoker llm;
    private final Prompts prompts;

    public EvalService(JdbcTemplate jdbc, LlmInvoker llm, Prompts prompts) {
        this.jdbc = jdbc;
        this.llm = llm;
        this.prompts = prompts;
    }

    public Map<String, Object> run(String source, int limit) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, content, human_label FROM moderation_gold WHERE source = ? AND human_label IN ('PASS','REVIEW','REJECT') ORDER BY id LIMIT ?",
                source, limit);
        Map<String, Map<String, Integer>> ruleConfusion = new TreeMap<>();
        long t0 = System.currentTimeMillis();
        int n = 0, modelOk = 0, ruleOk = 0, parseFail = 0, retries = 0;
        Map<String, Map<String, Integer>> confusion = new TreeMap<>();
        for (Map<String, Object> r : rows) {
            long id = ((Number) r.get("id")).longValue();
            String content = (String) r.get("content");
            String human = (String) r.get("human_label");
            String rule = KeywordRules.judge(content).label().name();
            String model = null;
            Double conf = null;
            // 与线上 ModerationService 同样的路径：解析失败重试一次
            String user = prompts.moderateUser(content);
            int max = ModerationService.MAX_TOKENS;
            for (int attempt = 0; attempt < 2 && model == null; attempt++) {
                try {
                    var reply = llm.invoke("eval", id, prompts.moderateSystem, user, true, max,
                            LlmInvoker.estimate(prompts.moderateSystem, user, max), Duration.ofSeconds(30));
                    var v = ModerationParser.parse(reply.content());
                    if (v.isPresent()) {
                        model = v.get().label().name();
                        conf = v.get().confidence();
                    } else {
                        if (attempt == 0) retries++;
                        log.warn("eval parse failed id={} attempt={} output={}", id, attempt + 1, reply.content());
                    }
                } catch (Exception e) {
                    if (attempt == 0) retries++;
                    log.warn("eval call failed id={} attempt={} err={}", id, attempt + 1, e.toString());
                }
            }
            if (model == null) parseFail++;
            jdbc.update("UPDATE moderation_gold SET model_label=?, model_conf=?, rule_label=? WHERE id=?", model, conf, rule, id);
            n++;
            if (human.equals(model)) modelOk++;
            if (human.equals(rule)) ruleOk++;
            confusion.computeIfAbsent(human, k -> new TreeMap<>()).merge(model == null ? "FAILED" : model, 1, Integer::sum);
            ruleConfusion.computeIfAbsent(human, k -> new TreeMap<>()).merge(rule, 1, Integer::sum);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source", source);
        out.put("elapsedSeconds", (System.currentTimeMillis() - t0) / 1000);
        out.put("samples", n);
        out.put("modelAccuracy", n == 0 ? 0 : (double) modelOk / n);
        out.put("ruleAccuracy", n == 0 ? 0 : (double) ruleOk / n);
        out.put("firstAttemptFailures", retries);
        out.put("modelFailures", parseFail);
        out.put("modelPerClass", perClass(confusion));
        out.put("rulePerClass", perClass(ruleConfusion));
        out.put("confusion(human -> model)", confusion);
        out.put("confusion(human -> rule)", ruleConfusion);
        return out;
    }

    /** 每个类别的精确率（判成该类的里有多少是对的）和召回率（该类有多少被判出来）。 */
    static Map<String, Map<String, Object>> perClass(Map<String, Map<String, Integer>> confusion) {
        Map<String, Map<String, Object>> out = new TreeMap<>();
        for (String c : List.of("PASS", "REVIEW", "REJECT")) {
            int tp = confusion.getOrDefault(c, Map.of()).getOrDefault(c, 0);
            int actual = confusion.getOrDefault(c, Map.of()).values().stream().mapToInt(Integer::intValue).sum();
            int predicted = confusion.values().stream().mapToInt(m -> m.getOrDefault(c, 0)).sum();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("support", actual);
            m.put("precision", predicted == 0 ? null : Math.round(1000.0 * tp / predicted) / 1000.0);
            m.put("recall", actual == 0 ? null : Math.round(1000.0 * tp / actual) / 1000.0);
            out.put(c, m);
        }
        return out;
    }
}

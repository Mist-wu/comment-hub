package io.commenthub.ai.service;

import io.commenthub.ai.guard.KeywordRules;
import io.commenthub.ai.llm.ModerationParser;
import io.commenthub.ai.llm.Prompts;
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
    private final JdbcTemplate jdbc;
    private final LlmInvoker llm;
    private final Prompts prompts;

    public EvalService(JdbcTemplate jdbc, LlmInvoker llm, Prompts prompts) {
        this.jdbc = jdbc;
        this.llm = llm;
        this.prompts = prompts;
    }

    public Map<String, Object> run(int limit) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, content, human_label FROM moderation_gold WHERE human_label IN ('PASS','REVIEW','REJECT') ORDER BY id LIMIT ?", limit);
        int n = 0, modelOk = 0, ruleOk = 0, parseFail = 0;
        Map<String, Map<String, Integer>> confusion = new TreeMap<>();
        for (Map<String, Object> r : rows) {
            long id = ((Number) r.get("id")).longValue();
            String content = (String) r.get("content");
            String human = (String) r.get("human_label");
            String rule = KeywordRules.judge(content).label().name();
            String model = null;
            Double conf = null;
            try {
                String user = prompts.moderateUser(content);
                var reply = llm.invoke("eval", id, prompts.moderateSystem, user, true, 80,
                        LlmInvoker.estimate(prompts.moderateSystem, user, 80), Duration.ofSeconds(30));
                var v = ModerationParser.parse(reply.content());
                if (v.isPresent()) {
                    model = v.get().label().name();
                    conf = v.get().confidence();
                } else {
                    parseFail++;
                }
            } catch (Exception e) {
                parseFail++;
            }
            jdbc.update("UPDATE moderation_gold SET model_label=?, model_conf=?, rule_label=? WHERE id=?", model, conf, rule, id);
            n++;
            if (human.equals(model)) modelOk++;
            if (human.equals(rule)) ruleOk++;
            confusion.computeIfAbsent(human, k -> new TreeMap<>()).merge(model == null ? "FAILED" : model, 1, Integer::sum);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("samples", n);
        out.put("modelAccuracy", n == 0 ? 0 : (double) modelOk / n);
        out.put("ruleAccuracy", n == 0 ? 0 : (double) ruleOk / n);
        out.put("modelFailures", parseFail);
        out.put("confusion(human -> model)", confusion);
        return out;
    }
}

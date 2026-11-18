package io.commenthub.ai.llm;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

/**
 * 模型调用记账，沿用 y2b-rs 的做法：调用开始先插一行 started，结束后回填状态、token、费用、耗时。
 * 进程在调用中途崩溃时会留下 started 行，能看出有多少调用“花了钱但没拿到结果”。
 */
@Component
public class CostLedger {
    private final JdbcTemplate jdbc;
    private final LlmProperties props;
    private final MeterRegistry meters;

    public CostLedger(JdbcTemplate jdbc, LlmProperties props, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.props = props;
        this.meters = meters;
    }

    public long start(String task, Long bizId) {
        GeneratedKeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO ai_call (task, provider, model, status, biz_id) VALUES (?, ?, ?, 'started', ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, task);
            ps.setString(2, props.provider());
            ps.setString(3, props.model());
            if (bizId == null) ps.setNull(4, java.sql.Types.BIGINT);
            else ps.setLong(4, bizId);
            return ps;
        }, kh);
        return kh.getKey().longValue();
    }

    public void finish(long id, String task, String status, LlmUsage u, long durationMs, String error) {
        Long cost = u.costMicroCny(props);
        jdbc.update("UPDATE ai_call SET status=?, input_tokens=?, cache_read_tokens=?, output_tokens=?, total_tokens=?, "
                        + "cost_micro_cny=?, duration_ms=?, error=?, finished_at=CURRENT_TIMESTAMP(3) WHERE id=?",
                status, u.input(), u.cacheRead(), u.output(), u.total(), cost, durationMs,
                error == null ? null : error.substring(0, Math.min(error.length(), 255)), id);
        meters.counter("hub.ai.tokens", "task", task, "kind", "input").increment(u.input());
        meters.counter("hub.ai.tokens", "task", task, "kind", "cache_read").increment(u.cacheRead());
        meters.counter("hub.ai.tokens", "task", task, "kind", "output").increment(u.output());
        if (cost != null) meters.counter("hub.ai.cost_micro_cny", "task", task).increment(cost);
    }

    /** 汇总，docs/cost.md 里的表就是用这个查询生成的。 */
    public List<Map<String, Object>> summary() {
        return jdbc.queryForList("SELECT provider, model, task, status, COUNT(*) AS calls, SUM(input_tokens) AS input_tokens, "
                + "SUM(cache_read_tokens) AS cache_read_tokens, SUM(output_tokens) AS output_tokens, SUM(total_tokens) AS total_tokens, "
                + "SUM(cost_micro_cny) / 1e6 AS cost_cny, ROUND(AVG(duration_ms)) AS avg_ms "
                + "FROM ai_call GROUP BY provider, model, task, status ORDER BY provider, task, status");
    }
}

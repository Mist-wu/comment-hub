package io.commenthub.ai.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.commenthub.common.trace.Trace;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** OpenAI 兼容的 chat/completions 调用。不做重试、不做降级，这些在 GuardedLlm 里统一处理。 */
@Component
public class LlmClient {
    public record Reply(String content, LlmUsage usage) {}

    private final LlmProperties props;
    private final ObjectMapper om;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    public LlmClient(LlmProperties props, ObjectMapper om) {
        this.props = props;
        this.om = om;
    }

    /**
     * @param timeout null 表示不设超时 —— 只有复盘 02 会这么用
     */
    public Reply chat(String system, String user, boolean jsonMode, int maxTokens, Duration timeout)
            throws IOException, InterruptedException {
        ObjectNode body = om.createObjectNode();
        body.put("model", props.model());
        body.put("temperature", 0);
        body.put("max_tokens", maxTokens);
        ArrayNode msgs = body.putArray("messages");
        msgs.addObject().put("role", "system").put("content", system);
        msgs.addObject().put("role", "user").put("content", user);
        if (jsonMode) body.putObject("response_format").put("type", "json_object");
        if (Boolean.TRUE.equals(props.disableThinking())) body.putObject("thinking").put("type", "disabled");

        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(props.baseUrl() + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .header(Trace.HTTP_HEADER, Trace.currentOrNew())
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        if (props.apiKey() != null && !props.apiKey().isBlank()) req.header("Authorization", "Bearer " + props.apiKey());
        if (timeout != null) req.timeout(timeout);

        HttpResponse<String> resp = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            String errBody = resp.body().length() > 200 ? resp.body().substring(0, 200) : resp.body();
            throw new IOException("upstream " + resp.statusCode() + ": " + errBody);
        }
        JsonNode root = om.readTree(resp.body());
        String content = root.path("choices").path(0).path("message").path("content").asText("");
        return new Reply(content, LlmUsage.parse(root.path("usage")));
    }
}

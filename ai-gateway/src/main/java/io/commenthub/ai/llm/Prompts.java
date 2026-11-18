package io.commenthub.ai.llm;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** 统一的 prompt 模板。用户内容用标签包起来，并去掉标签字符，防止评论里伪造指令边界。 */
@Component
public class Prompts {
    public final String moderateSystem;
    public final String summarizeSystem;

    public Prompts() throws IOException {
        moderateSystem = read("prompts/moderate.txt");
        summarizeSystem = read("prompts/summarize.txt");
    }

    public String moderateUser(String comment) {
        return "<comment>" + clean(comment) + "</comment>";
    }

    public String summarizeUser(List<String> comments) {
        StringBuilder sb = new StringBuilder("<comments>\n");
        for (String c : comments) sb.append(clean(c).replace('\n', ' ')).append('\n');
        return sb.append("</comments>").toString();
    }

    private static String clean(String s) {
        return s.replace("<", "").replace(">", "");
    }

    private static String read(String p) throws IOException {
        return new String(new ClassPathResource(p).getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
    }
}

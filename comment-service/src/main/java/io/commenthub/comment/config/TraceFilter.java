package io.commenthub.comment.config;

import io.commenthub.common.trace.Trace;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** 入口：取请求头里的 X-Trace-Id，没有就生成一个，放进 MDC，并回写到响应头。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws ServletException, IOException {
        String id = req.getHeader(Trace.HTTP_HEADER);
        try (var scope = Trace.open(id)) {
            resp.setHeader(Trace.HTTP_HEADER, Trace.current());
            chain.doFilter(req, resp);
        }
    }
}

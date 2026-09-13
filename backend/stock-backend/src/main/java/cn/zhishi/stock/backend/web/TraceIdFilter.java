package cn.zhishi.stock.backend.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.web.filter.OncePerRequestFilter;

public class TraceIdFilter extends OncePerRequestFilter {

    public static final String ATTRIBUTE = TraceIdFilter.class.getName() + ".traceId";
    public static final String HEADER = "X-Trace-Id";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String traceId = UUID.randomUUID().toString().replace("-", "");
        request.setAttribute(ATTRIBUTE, traceId);
        response.setHeader(HEADER, traceId);
        filterChain.doFilter(request, response);
    }

    public static String current(HttpServletRequest request) {
        Object traceId = request.getAttribute(ATTRIBUTE);
        return traceId == null ? "unknown" : traceId.toString();
    }
}

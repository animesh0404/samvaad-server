package com.samvaad.samvaad_server.common.logging;

import java.io.IOException;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Assigns a correlation identifier to every HTTP request, including the
 * WebSocket handshake. A valid caller-supplied {@code X-Request-ID} or
 * {@code X-Trace-ID} is propagated; otherwise a UUID is generated. The
 * identifier is available to log patterns as {@code traceId} and is echoed
 * back in the {@code X-Trace-ID} response header.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String traceId = TraceIds.resolveOrGenerate(
                request.getHeader(TraceIds.REQUEST_ID_HEADER),
                request.getHeader(TraceIds.TRACE_ID_HEADER));
        MDC.put(TraceIds.MDC_KEY, traceId);
        response.setHeader(TraceIds.TRACE_ID_HEADER, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(TraceIds.MDC_KEY);
        }
    }
}

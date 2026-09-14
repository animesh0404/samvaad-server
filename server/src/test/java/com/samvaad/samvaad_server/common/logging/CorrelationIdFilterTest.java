package com.samvaad.samvaad_server.common.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;

class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @Test
    void generatesTraceIdWhenAbsentAndClearsMdc() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> inChain = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> inChain.set(MDC.get(TraceIds.MDC_KEY)));

        UUID.fromString(inChain.get());
        assertEquals(inChain.get(), response.getHeader(TraceIds.TRACE_ID_HEADER));
        assertNull(MDC.get(TraceIds.MDC_KEY));
    }

    @Test
    void propagatesIncomingRequestId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TraceIds.REQUEST_ID_HEADER, "request-1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> inChain = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> inChain.set(MDC.get(TraceIds.MDC_KEY)));

        assertEquals("request-1", inChain.get());
        assertEquals("request-1", response.getHeader(TraceIds.TRACE_ID_HEADER));
        assertNull(MDC.get(TraceIds.MDC_KEY));
    }

    @Test
    void propagatesIncomingTraceIdWhenRequestIdAbsent() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TraceIds.TRACE_ID_HEADER, "trace-1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> inChain = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> inChain.set(MDC.get(TraceIds.MDC_KEY)));

        assertEquals("trace-1", inChain.get());
        assertEquals("trace-1", response.getHeader(TraceIds.TRACE_ID_HEADER));
        assertNull(MDC.get(TraceIds.MDC_KEY));
    }

    @Test
    void rejectsUnsafeIncomingId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TraceIds.REQUEST_ID_HEADER, "evil\nheader");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> inChain = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> inChain.set(MDC.get(TraceIds.MDC_KEY)));

        assertNotEquals("evil\nheader", inChain.get());
        UUID.fromString(inChain.get());
        assertNull(MDC.get(TraceIds.MDC_KEY));
    }

    @Test
    void clearsMdcEvenWhenChainFails() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain failing = (req, res) -> {
            throw new ServletException("boom");
        };

        assertThrows(ServletException.class, () -> filter.doFilter(request, response, failing));
        assertNull(MDC.get(TraceIds.MDC_KEY));
    }
}

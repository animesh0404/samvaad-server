package com.samvaad.samvaad_server.common.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import ch.qos.logback.classic.Level;

/**
 * Proves the generic AOP operational envelope: success/failure outcome,
 * duration, MDC traceId carriage, unchanged exception rethrow, no sensitive
 * data, no interception of unannotated methods, and exactly one envelope
 * line per invocation.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = OperationalLoggingAspectTest.Config.class)
class OperationalLoggingAspectTest {

    private static final String SECRET_PASSWORD = "aspect-password-secret-xyz";
    private static final String SECRET_TOKEN = "aspect-token-secret-xyz";
    private static final String SECRET_CONTENT = "aspect-content-secret-xyz";
    private static final IllegalStateException FAILURE = new IllegalStateException("boom");

    @Configuration
    @EnableAspectJAutoProxy
    static class Config {

        @Bean
        OperationalLoggingAspect operationalLoggingAspect() {
            return new OperationalLoggingAspect();
        }

        @Bean
        DemoService demoService() {
            return new DemoService();
        }
    }

    static class DemoService {

        @OperationalLog("demo.success")
        public String succeed(String password, String token, String content) {
            return "ok";
        }

        @OperationalLog("demo.failure")
        public String fail() {
            throw FAILURE;
        }

        public String unannotated() {
            return "plain";
        }
    }

    @Autowired
    private DemoService demoService;

    @Test
    void logsSuccessfulOperationOnce() {
        try (LogCapture logs = new LogCapture(OperationalLoggingAspect.class)) {
            demoService.succeed(SECRET_PASSWORD, SECRET_TOKEN, SECRET_CONTENT);

            assertEquals(1, logs.events().size(), "exactly one envelope line per invocation");
            assertEquals(Level.DEBUG, logs.events().get(0).getLevel());
            String message = logs.events().get(0).getFormattedMessage();
            assertTrue(message.contains("operation=demo.success"), message);
            assertTrue(message.contains("outcome=SUCCESS"), message);
        }
    }

    @Test
    void logsFailedOperationWithExceptionType() {
        try (LogCapture logs = new LogCapture(OperationalLoggingAspect.class)) {
            assertThrows(IllegalStateException.class, () -> demoService.fail());

            assertEquals(1, logs.events().size(), "exactly one envelope line per invocation");
            String message = logs.events().get(0).getFormattedMessage();
            assertTrue(message.contains("operation=demo.failure"), message);
            assertTrue(message.contains("outcome=FAILURE"), message);
            assertTrue(message.contains("exceptionType=IllegalStateException"), message);
        }
    }

    @Test
    void durationIsPresent() {
        Pattern duration = Pattern.compile("durationMs=(\\d+)");
        try (LogCapture logs = new LogCapture(OperationalLoggingAspect.class)) {
            demoService.succeed(SECRET_PASSWORD, SECRET_TOKEN, SECRET_CONTENT);

            Matcher matcher = duration.matcher(logs.events().get(0).getFormattedMessage());
            assertTrue(matcher.find(), "durationMs must be present");
            assertTrue(Long.parseLong(matcher.group(1)) >= 0);
        }
    }

    @Test
    void traceIdFromMdcAppears() {
        MDC.put(TraceIds.MDC_KEY, "trace-123");
        try (LogCapture logs = new LogCapture(OperationalLoggingAspect.class)) {
            demoService.succeed(SECRET_PASSWORD, SECRET_TOKEN, SECRET_CONTENT);

            assertEquals("trace-123",
                    logs.events().get(0).getMDCPropertyMap().get(TraceIds.MDC_KEY));
        } finally {
            MDC.remove(TraceIds.MDC_KEY);
        }
    }

    @Test
    void exceptionIsRethrownUnchanged() {
        try (LogCapture logs = new LogCapture(OperationalLoggingAspect.class)) {
            Throwable thrown = assertThrows(IllegalStateException.class, () -> demoService.fail());
            assertSame(FAILURE, thrown, "aspect must never consume, replace, or alter exceptions");
        }
    }

    @Test
    void sensitiveArgumentsAreNeverLogged() {
        try (LogCapture logs = new LogCapture(OperationalLoggingAspect.class)) {
            demoService.succeed(SECRET_PASSWORD, SECRET_TOKEN, SECRET_CONTENT);

            String text = logs.text();
            assertTrue(!text.contains(SECRET_PASSWORD), "password must never be logged");
            assertTrue(!text.contains(SECRET_TOKEN), "token must never be logged");
            assertTrue(!text.contains(SECRET_CONTENT), "content must never be logged");
        }
    }

    @Test
    void unannotatedMethodsAreNotIntercepted() {
        try (LogCapture logs = new LogCapture(OperationalLoggingAspect.class)) {
            assertEquals("plain", demoService.unannotated());

            assertTrue(logs.events().isEmpty(), "unannotated methods must not be intercepted");
        }
    }
}

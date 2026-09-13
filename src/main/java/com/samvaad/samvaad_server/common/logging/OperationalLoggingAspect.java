package com.samvaad.samvaad_server.common.logging;

import java.util.concurrent.TimeUnit;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Generic cross-cutting operational envelope for explicitly selected
 * service-layer business operations (see {@link OperationalLog}).
 *
 * <p>Each intercepted invocation emits exactly one diagnostic line carrying
 * the operation name, outcome ({@code SUCCESS}/{@code FAILURE}), duration,
 * and — on failure — the exception type. The existing {@code traceId} MDC
 * entry, populated by {@code CorrelationIdFilter} (HTTP) or the STOMP
 * interception path, is carried by the log pattern; this aspect never creates
 * correlation identifiers.
 *
 * <p>The envelope is logged at DEBUG so it stays distinct from the
 * INFO/WARN domain-specific event logs owned by the services: business
 * meaning (identifiers, state transitions) is never duplicated here, and
 * security-relevant failures keep their single WARN at the owning boundary.
 *
 * <p>Failures are always rethrown unchanged: this aspect never consumes,
 * replaces, or alters exceptions, and it never logs arguments, return
 * values, or sensitive data.
 */
@Aspect
@Component
public class OperationalLoggingAspect {

    private static final Logger log = LoggerFactory.getLogger(OperationalLoggingAspect.class);

    @Around("@annotation(operationalLog)")
    public Object logOperation(ProceedingJoinPoint joinPoint, OperationalLog operationalLog)
            throws Throwable {
        long start = System.nanoTime();
        try {
            Object result = joinPoint.proceed();
            log.debug("operation={} outcome=SUCCESS durationMs={}",
                    operationalLog.value(), durationMs(start));
            return result;
        } catch (Throwable failure) {
            log.debug("operation={} outcome=FAILURE durationMs={} exceptionType={}",
                    operationalLog.value(), durationMs(start), failure.getClass().getSimpleName());
            throw failure;
        }
    }

    private static long durationMs(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }
}

package com.samvaad.samvaad_server.common.logging;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Opts a service-layer business method into generic operational logging by
 * {@link OperationalLoggingAspect}. The value names the business operation
 * (for example {@code "message.send"}).
 *
 * <p>The aspect records only the operation envelope: name, duration, outcome,
 * and exception type on failure. It never logs method arguments, return
 * values, or sensitive data. Domain-specific details (identifiers, state
 * transitions) stay in the owning service where they carry business meaning.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface OperationalLog {

    String value();
}

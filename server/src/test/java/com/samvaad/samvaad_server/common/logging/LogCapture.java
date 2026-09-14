package com.samvaad.samvaad_server.common.logging;

import java.util.List;

import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Attaches a Logback {@link ListAppender} to a logger for focused logging tests.
 * Temporarily lowers the logger level to DEBUG so level filtering does not
 * hide captured events; the previous level is restored on close.
 */
public final class LogCapture implements AutoCloseable {

    private final Logger logger;
    private final ListAppender<ILoggingEvent> appender;
    private final Level previousLevel;

    public LogCapture(Class<?> loggedClass) {
        this.logger = (Logger) LoggerFactory.getLogger(loggedClass);
        this.previousLevel = logger.getLevel();
        this.logger.setLevel(Level.DEBUG);
        this.appender = new ListAppender<>();
        this.appender.start();
        this.logger.addAppender(appender);
    }

    public List<ILoggingEvent> events() {
        return appender.list;
    }

    public String text() {
        StringBuilder sb = new StringBuilder();
        for (ILoggingEvent event : appender.list) {
            sb.append(event.getLevel()).append(' ').append(event.getFormattedMessage()).append('\n');
        }
        return sb.toString();
    }

    @Override
    public void close() {
        logger.detachAppender(appender);
        appender.stop();
        logger.setLevel(previousLevel);
    }
}

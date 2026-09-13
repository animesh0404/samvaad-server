package com.samvaad.samvaad_server.common.logging;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/**
 * Guards the operational file-logging contract: console retention,
 * size-based rolling, compressed archives, bounded retention defaulting to
 * 50 rolled files, configurability, and traceId in log patterns.
 */
class LogbackConfigTest {

    private String logbackXml() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/logback-spring.xml")) {
            if (in == null) {
                throw new IllegalStateException("logback-spring.xml not on test classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String testApplicationYaml() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/application.yaml")) {
            if (in == null) {
                throw new IllegalStateException("application.yaml not on test classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void keepsConsoleLogging() throws Exception {
        assertTrue(logbackXml().contains("ConsoleAppender"));
    }

    @Test
    void configuresSizeBasedRollingWithCompressedArchives() throws Exception {
        String xml = logbackXml();
        assertTrue(xml.contains("RollingFileAppender"));
        assertTrue(xml.contains("SizeBasedTriggeringPolicy"));
        assertTrue(xml.contains("maxFileSize"));
        assertTrue(xml.contains(".gz"));
    }

    @Test
    void boundsRetentionWithFiftyFileDefault() throws Exception {
        String xml = logbackXml();
        assertTrue(xml.contains("FixedWindowRollingPolicy"));
        assertTrue(xml.contains("maxIndex"));
        assertTrue(xml.contains("defaultValue=\"50\""));
    }

    @Test
    void keepsFilePathSizeAndHistoryConfigurable() throws Exception {
        String xml = logbackXml();
        assertTrue(xml.contains("samvaad.logging.file.name"));
        assertTrue(xml.contains("samvaad.logging.file.max-size"));
        assertTrue(xml.contains("samvaad.logging.file.max-history"));
    }

    @Test
    void includesTraceIdInLogPatterns() throws Exception {
        assertTrue(logbackXml().contains("%X{traceId}"));
    }

    @Test
    void testsDoNotWriteRepositoryLogFiles() throws Exception {
        String yaml = testApplicationYaml();
        assertTrue(yaml.contains("build/logs/"),
                "test logging file must stay under gitignored build/, was:\n" + yaml);
    }
}

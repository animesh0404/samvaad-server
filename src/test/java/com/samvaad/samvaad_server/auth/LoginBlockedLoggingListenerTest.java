package com.samvaad.samvaad_server.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.samvaad.samvaad_server.auth.event.LoginBlockedDueToSessionLimitEvent;
import com.samvaad.samvaad_server.common.logging.LogCapture;
import com.samvaad.samvaad_server.session.ClientPlatform;

import ch.qos.logback.classic.Level;

class LoginBlockedLoggingListenerTest {

    @Test
    void logsBlockedLoginAtWarnWithSafeContext() {
        UUID userId = UUID.randomUUID();
        LoginBlockedLoggingListener listener = new LoginBlockedLoggingListener();

        try (LogCapture logs = new LogCapture(LoginBlockedLoggingListener.class)) {
            listener.onLoginBlocked(new LoginBlockedDueToSessionLimitEvent(
                    userId,
                    Instant.now(),
                    "inst-1",
                    ClientPlatform.WEB,
                    "Web Client",
                    "1.0.0",
                    "127.0.0.1",
                    "Mozilla/5.0"));

            assertEquals(1, logs.events().size());
            assertEquals(Level.WARN, logs.events().get(0).getLevel());
            String text = logs.text();
            assertTrue(text.contains(userId.toString()));
            assertTrue(text.contains("session limit"));
        }
    }
}

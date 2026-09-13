package com.samvaad.samvaad_server.auth;

import com.samvaad.samvaad_server.auth.event.LoginBlockedDueToSessionLimitEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Operational security logging for logins blocked by the session limit.
 * This is the single logging point for the block: the publishing site in
 * {@link AuthenticationService} stays log-free so the event is not logged twice.
 */
@Component
public class LoginBlockedLoggingListener {

    private static final Logger log = LoggerFactory.getLogger(LoginBlockedLoggingListener.class);

    @EventListener
    public void onLoginBlocked(LoginBlockedDueToSessionLimitEvent event) {
        log.warn("Login blocked: session limit reached userId={} clientPlatform={} ipAddress={}",
                event.userId(), event.clientPlatform(), event.ipAddress());
    }
}

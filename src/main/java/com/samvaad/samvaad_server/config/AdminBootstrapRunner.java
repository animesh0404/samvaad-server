package com.samvaad.samvaad_server.config;

import com.samvaad.samvaad_server.user.AdminBootstrapService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class AdminBootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrapRunner.class);

    private final AdminBootstrapService adminBootstrapService;
    private final BootstrapAdminProperties properties;

    public AdminBootstrapRunner(
            AdminBootstrapService adminBootstrapService,
            BootstrapAdminProperties properties) {
        this.adminBootstrapService = adminBootstrapService;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.hasCredentials()) {
            log.warn("Bootstrap admin credentials are absent; no administrator was created.");
            return;
        }
        adminBootstrapService.bootstrap(properties);
        log.info("Bootstrap admin check complete.");
    }
}
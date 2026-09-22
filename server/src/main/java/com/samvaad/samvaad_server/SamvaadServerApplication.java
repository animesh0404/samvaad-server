package com.samvaad.samvaad_server;

import com.samvaad.samvaad_server.tls.TlsBootstrap;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@SpringBootApplication
@EnableJpaAuditing
public class SamvaadServerApplication {

    public static void main(String[] args) {
        // External operator configuration and TLS identity are prepared
        // before the embedded servlet container exists: the keystore must be
        // generated/validated first so Spring Boot can open the HTTPS
        // connector during context refresh (ADR 0017). There is no insecure
        // HTTP listener.
        TlsBootstrap.TlsReady tls = TlsBootstrap.ensureReady();
        new SpringApplicationBuilder(SamvaadServerApplication.class)
                .properties(tls.springProperties())
                .run(args);
    }

}

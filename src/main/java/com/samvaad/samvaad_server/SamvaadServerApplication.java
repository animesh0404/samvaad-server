package com.samvaad.samvaad_server;

import com.samvaad.samvaad_server.config.BootstrapAdminProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@SpringBootApplication
@EnableJpaAuditing
@EnableConfigurationProperties(BootstrapAdminProperties.class)
public class SamvaadServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(SamvaadServerApplication.class, args);
    }

}

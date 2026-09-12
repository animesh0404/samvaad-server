package com.samvaad.samvaad_server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@SpringBootApplication
@EnableJpaAuditing
public class SamvaadServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(SamvaadServerApplication.class, args);
    }

}

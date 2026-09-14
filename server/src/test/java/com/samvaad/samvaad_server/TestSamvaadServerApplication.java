package com.samvaad.samvaad_server;

import org.springframework.boot.SpringApplication;

public class TestSamvaadServerApplication {

	public static void main(String[] args) {
		SpringApplication.from(SamvaadServerApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}

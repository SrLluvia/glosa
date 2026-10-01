package dev.glosa.core;

import org.springframework.boot.SpringApplication;

public class TestGlosaCoreApplication {

	public static void main(String[] args) {
		SpringApplication.from(GlosaCoreApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}

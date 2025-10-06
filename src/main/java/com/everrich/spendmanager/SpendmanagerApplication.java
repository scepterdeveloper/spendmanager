package com.everrich.spendmanager;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class SpendmanagerApplication {

	public static void main(String[] args) {
		SpringApplication.run(SpendmanagerApplication.class, args);
	}

}

package com.capitec.fraud;

import org.springframework.boot.SpringApplication;

public class TestFraudRuleEngineApplication {

	public static void main(String[] args) {
		SpringApplication.from(FraudRuleEngineApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}

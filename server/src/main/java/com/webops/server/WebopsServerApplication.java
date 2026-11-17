package com.webops.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling // 정기 검사 시계(SiteScheduler)

public class WebopsServerApplication {

	public static void main(String[] args) {
		SpringApplication.run(WebopsServerApplication.class, args);
	}

}

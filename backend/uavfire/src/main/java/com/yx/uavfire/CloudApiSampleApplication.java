package com.yx.uavfire;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@MapperScan("com.yx.uavfire.*.dao")
@SpringBootApplication
@EnableScheduling
@ComponentScan("com.dji")
public class CloudApiSampleApplication {

	public static void main(String[] args) {
		SpringApplication.run(CloudApiSampleApplication.class, args);
	}

}

package com.mediprice.batch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class BatchApplication {

	public static void main(String[] args) {
		// 배치가 끝나면 종료 코드(성공 0, 실패 1)를 넘기고 프로그램 종료
		System.exit(SpringApplication.exit(SpringApplication.run(BatchApplication.class, args)));
	}

}

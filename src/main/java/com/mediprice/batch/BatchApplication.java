package com.mediprice.batch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class BatchApplication {

	public static void main(String[] args) {
		// Spring Batch 실행 기록(timestamp without time zone)과 JDBC 세션이 JVM 기본 시간대를 따르므로 컨텍스트 생성 전에 고정
		KoreaTime.applyAsDefault();
		// 배치가 끝나면 종료 코드(성공 0, 실패 1)를 넘기고 프로그램 종료
		System.exit(SpringApplication.exit(SpringApplication.run(BatchApplication.class, args)));
	}

}

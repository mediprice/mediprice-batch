package com.mediprice.batch;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
class BatchApplicationTests {

	// 실행기를 가짜로 바꿈. 안 바꾸면 컨텍스트가 뜰 때 .env의 DB에 실제 배치가 돌아감
	@MockitoBean
	private HiraBatchRunner hiraBatchRunner;

	@Test
	void contextLoads() {
	}

}

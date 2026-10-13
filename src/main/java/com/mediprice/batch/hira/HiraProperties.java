package com.mediprice.batch.hira;

import org.springframework.boot.context.properties.ConfigurationProperties;

// application.yaml의 hira.* 설정값
@ConfigurationProperties(prefix = "hira")

public record HiraProperties(String baseUrl, String serviceKey) {
    // baseUrl : HIRA API 기본 주소 (테스트 서버는 .env의 HIRA_BASE_URL로 변경)
    // serviceKey : 공공데이터포털 인증키 (.env의 HIRA_SERVICE_KEY)
}

package com.mediprice.batch.hira;

import org.springframework.boot.context.properties.ConfigurationProperties;

// application.yaml의 hira.* 설정값
@ConfigurationProperties(prefix = "hira")
public record HiraProperties(String baseUrl, String serviceKey) {
}

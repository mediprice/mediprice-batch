package com.mediprice.batch;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class KoreaTimeConfig {

    @Bean
    Clock clock() {
        return KoreaTime.clock();
    }
}

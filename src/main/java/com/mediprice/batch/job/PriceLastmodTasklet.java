package com.mediprice.batch.job;

import java.time.Clock;
import java.time.LocalDate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import com.mediprice.batch.KoreaTime;

// 병원 상세의 사이트맵 lastmod(price_lastmod)를 채움: 그 병원 가격 중 가장 최근 적용 시작일(adt_fr_dd)
// 백엔드 사이트맵이 예전에 가격 테이블에서 계산하던 값과 같게 오늘(한국 날짜) 이후 시작일은 뺌. 가격이 없으면 null
// 값이 달라진 행만 씀. 파생값이라 실패하면 Step을 실패시킴 (옛 lastmod를 조용히 내보내지 않도록. 다시 실행하면 이 Step부터 이어감)
@Component
public class PriceLastmodTasklet implements Tasklet {

    private static final Logger log = LoggerFactory.getLogger(PriceLastmodTasklet.class);

    static final String SQL = """
            UPDATE hospital_detail d
            SET price_lastmod = m.lastmod
            FROM (SELECT hd.ykiho, a.lastmod
                  FROM hospital_detail hd
                  LEFT JOIN (SELECT ykiho, max(adt_fr_dd) AS lastmod
                             FROM hospital_npay_price
                             WHERE adt_fr_dd <= :today
                             GROUP BY ykiho) a ON a.ykiho = hd.ykiho) m
            WHERE d.ykiho = m.ykiho AND d.price_lastmod IS DISTINCT FROM m.lastmod
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final Clock clock;

    @Autowired
    public PriceLastmodTasklet(NamedParameterJdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, KoreaTime.clock());
    }

    PriceLastmodTasklet(NamedParameterJdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        int changed = jdbcTemplate.update(SQL, new MapSqlParameterSource("today", LocalDate.now(clock)));
        log.info("hospital_detail.price_lastmod 갱신 {}행", changed);
        return RepeatStatus.FINISHED;
    }
}

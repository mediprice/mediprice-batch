package com.mediprice.batch.job;

import java.util.List;

import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.item.ItemStreamReader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

// 가격 데이터가 있는 병원의 ykiho를 하나씩 돌려주는 Reader. 다 읽으면 null
// 목록은 Step 시작 때 DB에서 읽음 (가격 Step이 끝난 뒤의 최신 목록)
@Component
public class PricedHospitalReader implements ItemStreamReader<String> {

    private static final String SQL = "SELECT DISTINCT ykiho FROM hospital_npay_price ORDER BY ykiho";
    // 이어서 실행할 때 쓰는 저장 키. 다음에 읽을 위치를 Step 실행 기록에 남김
    private static final String INDEX_KEY = "detail.index";

    private final JdbcTemplate jdbcTemplate;
    private List<String> ykihos = List.of();
    private int index;

    public PricedHospitalReader(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // 이어서 실행이면 저장된 위치부터 (상세정보 API 하루 한도 때문에 처음부터 다시 부르지 않게)
    @Override
    public void open(ExecutionContext executionContext) {
        ykihos = jdbcTemplate.queryForList(SQL, String.class);
        index = executionContext.getInt(INDEX_KEY, 0);
    }

    @Override
    public void update(ExecutionContext executionContext) {
        executionContext.putInt(INDEX_KEY, index);
    }

    @Override
    public String read() {
        return index < ykihos.size() ? ykihos.get(index++) : null;
    }
}

package com.mediprice.batch.job;

import java.util.Map;

import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.springframework.stereotype.Component;

import com.mediprice.batch.hira.HiraClient;
import com.mediprice.batch.hira.HiraPage;

// ykiho로 세부정보(진료시간·주차·응급실)를 호출해 저장할 행으로 바꿈 (의료기관별상세정보서비스 getDtlInfo2.8)
// 데이터가 없는 병원은 0건 정상 응답이 와서 detail = null
@Component
public class HospitalDetailProcessor implements ItemProcessor<String, HospitalDetailProcessor.DetailRow> {

    // ykiho : 병원 고유키
    // detail : 세부정보 응답 item 1건 (없으면 null)
    public record DetailRow(String ykiho, Map<String, String> detail) {
    }

    private final HiraClient hiraClient;

    public HospitalDetailProcessor(HiraClient hiraClient) {
        this.hiraClient = hiraClient;
    }

    @Override
    public DetailRow process(String ykiho) {
        HiraPage page = hiraClient.get("/MadmDtlInfoService2.8/getDtlInfo2.8",
                Map.of("ykiho", ykiho, "pageNo", "1", "numOfRows", "10"));
        return new DetailRow(ykiho, page.items().isEmpty() ? null : page.items().get(0));
    }
}

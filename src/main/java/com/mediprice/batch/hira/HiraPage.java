package com.mediprice.batch.hira;

import java.util.List;
import java.util.Map;

public record HiraPage(List<Map<String, String>> items, int totalCount) {
    //items : item목록, item 하나 = {필드 이름: 값}
    //totalCount : 조건에 맞는 전체 건수 (여러 페이지에 걸친 합계)
}

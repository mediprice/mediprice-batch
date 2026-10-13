package com.mediprice.batch.hira;

// HIRA 오류 예외. code 예: 22 호출 한도 초과, 30 미등록 서비스키, PARSE XML 아닌 응답
public class HiraApiException extends RuntimeException {

    private final String code;

    public HiraApiException(String code, String message) {
        super("[" + code + "] " + message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}

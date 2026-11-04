package com.mediprice.batch.hira;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

class HiraClientTest {

    private static final String KEY = "ab+cd/ef==";
    private static final String OK_XML = """
            <response><header><resultCode>00</resultCode><resultMsg>OK</resultMsg></header>
            <body><items><item><ykiho>A</ykiho><yadmNm> 병원 </yadmNm></item><item><ykiho>B</ykiho></item></items>
            <totalCount>12</totalCount></body></response>
            """;

    private final Deque<String> bodies = new ArrayDeque<>();
    private HttpServer server;
    private HiraClient client;
    private String location;
    private int calls;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", this::respond);
        server.start();
        client = new HiraClient(RestClient.builder(),
                new HiraProperties("http://localhost:" + server.getAddress().getPort(), KEY)) {
            @Override
            void sleep(Duration duration) {
            }
        };
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    // 큐의 응답을 차례로 돌려주고, 마지막 응답은 계속 반복
    private void respond(HttpExchange exchange) throws IOException {
        calls++;
        if (location != null) {
            exchange.getResponseHeaders().add("Location", location.replace("{query}", exchange.getRequestURI().getRawQuery()));
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
            return;
        }
        byte[] body = (bodies.size() > 1 ? bodies.poll() : bodies.peek()).getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static String resultXml(String code) {
        return "<response><header><resultCode>" + code + "</resultCode><resultMsg>ERR</resultMsg></header></response>";
    }

    private static String gatewayXml(String code) {
        return "<OpenAPI_ServiceResponse><cmmMsgHeader><returnAuthMsg>ERR</returnAuthMsg><returnReasonCode>" + code
                + "</returnReasonCode></cmmMsgHeader></OpenAPI_ServiceResponse>";
    }

    private HiraPage get() {
        return client.get("/test", Map.of("pageNo", "1"));
    }

    private void assertFailsWith(String code, int expectedCalls) {
        assertThatThrownBy(this::get)
                .isInstanceOfSatisfying(HiraApiException.class, e -> assertThat(e.getCode()).isEqualTo(code));
        assertThat(calls).isEqualTo(expectedCalls);
    }

    @Test
    void 정상_응답을_파싱함() {
        bodies.add(OK_XML);

        HiraPage page = get();

        assertThat(page.totalCount()).isEqualTo(12);
        assertThat(page.items()).containsExactly(Map.of("ykiho", "A", "yadmNm", "병원"), Map.of("ykiho", "B"));
    }

    @Test
    void 호출_한도_초과는_다시_시도하지_않음() {
        bodies.add(gatewayXml("22"));

        assertFailsWith("22", 1);
    }

    @Test
    void 키_오류는_다시_시도하지_않음() {
        bodies.add(gatewayXml("30"));

        assertFailsWith("30", 1);
    }

    @Test
    void 일시_오류_뒤_정상이면_성공함() {
        bodies.add(resultXml("01"));
        bodies.add(OK_XML);

        assertThat(get().totalCount()).isEqualTo(12);
        assertThat(calls).isEqualTo(2);
    }

    @Test
    void 일시_오류가_계속되면_3번_호출_후_실패함() {
        bodies.add(resultXml("01"));

        assertFailsWith("01", 3);
    }

    @Test
    void XML이_아니면_PARSE로_다시_시도함() {
        bodies.add("not xml");

        assertFailsWith("PARSE", 3);
    }

    @Test
    void DOCTYPE이_있으면_거부함() {
        bodies.add("<!DOCTYPE foo [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><response>&x;</response>");

        assertFailsWith("PARSE", 3);
    }

    @Test
    void 빈_응답의_Location에_인증키를_남기지_않음() {
        location = "https://portal.example/err?{query}";

        assertThatThrownBy(this::get)
                .isInstanceOf(HiraApiException.class)
                .hasMessageContaining("HTTP 302")
                .hasMessageContaining("portal.example/err")
                .hasMessageNotContaining("ab+cd")
                .hasMessageNotContaining("serviceKey");
    }

    @Test
    void Location_안에_인코딩된_주소가_있어도_인증키를_남기지_않음() {
        location = "https://portal.example/err?returnUrl=https%3A%2F%2Fapis%3FserviceKey%3Dab%252Bcd%252Fef";

        assertThatThrownBy(this::get)
                .isInstanceOf(HiraApiException.class)
                .hasMessageNotContaining("ab%252Bcd");
    }
}

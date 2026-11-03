package com.mediprice.batch.hira;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.web.client.RestClient;

class HiraPagingItemReaderTest {

    private static final int PAGE_SIZE = 10;
    private static final int TOTAL = 45;
    private static final String KEY = "test.totalCount";

    private FakeHira hira;
    private ExecutionContext context;
    private Set<String> saved;

    @BeforeEach
    void setUp() {
        hira = new FakeHira();
        for (int i = 0; i < TOTAL; i++) {
            hira.rows.add("r" + i);
        }
        context = new ExecutionContext();
        saved = new HashSet<>();
    }

    @Test
    void 변화가_없으면_전부_읽음() {
        runStep(-1);

        assertThat(saved).hasSize(TOTAL);
        assertThat(hira.calls).isEqualTo(5);
    }

    @Test
    void 변화가_없는_재시작은_이어서_읽음() {
        assertThatThrownBy(() -> runStep(2)).isInstanceOf(Crash.class);

        runStep(-1);

        assertThat(saved).hasSize(TOTAL);
        assertThat(hira.calls).isEqualTo(5);
    }

    @Test
    void 재시작_사이에_앞쪽_행이_빠져도_밀린_행을_놓치지_않음() {
        assertThatThrownBy(() -> runStep(2)).isInstanceOf(Crash.class);
        hira.rows.remove("r5");

        runStep(-1);

        assertThat(saved).contains("r20");
        assertThat(saved).containsAll(hira.rows);
    }

    @Test
    void 재시작_없이_읽는_중_앞쪽_행이_빠져도_밀린_행을_놓치지_않음() {
        hira.beforePage.put(3, () -> hira.rows.remove("r5"));

        runStep(-1);

        assertThat(saved).contains("r20");
        assertThat(saved).containsAll(hira.rows);
    }

    @Test
    void 읽는_중_앞쪽에_행이_추가돼도_새_행을_읽음() {
        hira.beforePage.put(3, () -> hira.rows.add(0, "new"));

        runStep(-1);

        assertThat(saved).contains("new");
        assertThat(saved).containsAll(hira.rows);
    }

    @Test
    void 전체_건수가_계속_바뀌면_실패() {
        hira.changeOnEveryCall = true;

        assertThatThrownBy(() -> runStep(-1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("계속 바뀜");
    }

    @Test
    void 마지막_페이지_저장_직후_꺼져도_재시작이_성공함() {
        assertThatThrownBy(() -> runStep(5)).isInstanceOf(Crash.class);
        int calls = hira.calls;

        runStep(-1);

        assertThat(saved).hasSize(TOTAL);
        assertThat(hira.calls).isEqualTo(calls);
    }

    @Test
    void 전체_건수를_실행_기록에_남김() {
        runStep(-1);

        assertThat(context.getInt(KEY)).isEqualTo(TOTAL);
    }

    // Step 순서대로 실행: open → 반복(chunk 읽기, 저장, update). crashAfterCommits번 저장 후 꺼진 것처럼 멈춤
    private void runStep(int crashAfterCommits) {
        HiraPagingItemReader reader = new HiraPagingItemReader(hira, "/test", Map.of(), PAGE_SIZE, KEY);
        reader.open(context);
        int commits = 0;
        while (true) {
            List<String> chunk = new ArrayList<>();
            boolean end = false;
            while (chunk.size() < PAGE_SIZE) {
                Map<String, String> item = reader.read();
                if (item == null) {
                    end = true;
                    break;
                }
                chunk.add(item.get("key"));
            }
            saved.addAll(chunk);
            reader.update(context);
            if (++commits == crashAfterCommits) {
                throw new Crash();
            }
            if (end) {
                return;
            }
        }
    }

    private static class Crash extends RuntimeException {
    }

    // 메모리의 행 목록을 페이지로 잘라 돌려주는 가짜 HIRA
    private static class FakeHira extends HiraClient {

        final List<String> rows = new ArrayList<>();
        final Map<Integer, Runnable> beforePage = new HashMap<>();
        boolean changeOnEveryCall;
        int calls;

        FakeHira() {
            super(RestClient.builder(), new HiraProperties("http://localhost", "test-key"));
        }

        @Override
        public HiraPage get(String path, Map<String, String> params) {
            int page = Integer.parseInt(params.get("pageNo"));
            int size = Integer.parseInt(params.get("numOfRows"));
            Runnable change = beforePage.remove(page);
            if (change != null) {
                change.run();
            }
            if (changeOnEveryCall) {
                rows.add("added" + calls);
            }
            calls++;

            int from = Math.min((page - 1) * size, rows.size());
            int to = Math.min(page * size, rows.size());
            List<Map<String, String>> items = new ArrayList<>();
            for (String key : rows.subList(from, to)) {
                items.add(Map.of("key", key));
            }
            return new HiraPage(items, rows.size());
        }
    }
}

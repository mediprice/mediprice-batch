package com.mediprice.batch.hira;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.item.ItemStreamReader;

// HIRA 목록을 페이지 단위로 받아 item을 하나씩 돌려주는 Reader. 다 읽으면 null
public class HiraPagingItemReader implements ItemStreamReader<Map<String, String>> {

    private final HiraClient hiraClient;
    private final String path;
    private final Map<String, String> params;
    private final int pageSize;

    private final Deque<Map<String, String>> buffer = new ArrayDeque<>();
    private int nextPage;
    private int totalCount; // -1 = 아직 첫 페이지를 안 받음
    private int readCount;

    public HiraPagingItemReader(HiraClient hiraClient, String path, Map<String, String> params, int pageSize) {
        this.hiraClient = hiraClient;
        this.path = path;
        this.params = params;
        this.pageSize = pageSize;
        reset();
    }

    // Step 시작 때 호출됨. 이전 실행 상태를 비움
    @Override
    public void open(ExecutionContext executionContext) {
        reset();
    }

    @Override
    public Map<String, String> read() {
        if (buffer.isEmpty() && hasMorePages()) {
            fetchNextPage();
        }
        if (buffer.isEmpty()) {
            checkAllRead();
            return null;
        }
        readCount++;
        return buffer.poll();
    }

    private void reset() {
        buffer.clear();
        nextPage = 1;
        totalCount = -1;
        readCount = 0;
    }

    private boolean hasMorePages() {
        return totalCount < 0 || (long) (nextPage - 1) * pageSize < totalCount;
    }

    private void fetchNextPage() {
        Map<String, String> query = new HashMap<>(params);
        query.put("pageNo", String.valueOf(nextPage));
        query.put("numOfRows", String.valueOf(pageSize));

        HiraPage page = hiraClient.get(path, query);
        totalCount = page.totalCount();
        buffer.addAll(page.items());
        nextPage++;
    }

    // 전체 건수만큼 다 읽었는지 확인. 중간에 데이터가 바뀌거나 페이지가 비면 실패 처리
    private void checkAllRead() {
        if (totalCount >= 0 && readCount != totalCount) {
            throw new IllegalStateException(path + " 읽은 건수와 전체 건수가 다름: "
                    + readCount + " / " + totalCount);
        }
    }
}

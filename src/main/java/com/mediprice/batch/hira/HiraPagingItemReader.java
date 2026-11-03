package com.mediprice.batch.hira;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.item.ItemStreamReader;

// HIRA 목록을 페이지 단위로 받아 item을 하나씩 돌려주는 Reader. 다 읽으면 null
public class HiraPagingItemReader implements ItemStreamReader<Map<String, String>> {

    private static final Logger log = LoggerFactory.getLogger(HiraPagingItemReader.class);

    // 이어서 실행할 때 쓰는 저장 키. 다음에 받을 페이지 번호를 Step 실행 기록에 남김
    private static final String NEXT_PAGE_KEY = "hira.nextPage";
    // 전체 건수가 바뀌어 1페이지부터 다시 읽는 최대 횟수. 넘으면 실패 (호출 한도 보호)
    private static final int MAX_REWINDS = 3;

    private final HiraClient hiraClient;
    private final String path;
    private final Map<String, String> params;
    private final int pageSize;
    private final String totalCountKey; // 전체 건수를 Step 실행 기록에 남기는 키

    private final Deque<Map<String, String>> buffer = new ArrayDeque<>();
    private int nextPage;
    private int totalCount; // -1 = 아직 첫 페이지를 안 받음
    private int readCount;
    private int rewinds;

    public HiraPagingItemReader(HiraClient hiraClient, String path, Map<String, String> params, int pageSize, String totalCountKey) {
        this.hiraClient = hiraClient;
        this.path = path;
        this.params = params;
        this.pageSize = pageSize;
        this.totalCountKey = totalCountKey;
        reset();
    }

    // Step 시작 때 호출됨. 이어서 실행이면 저장된 페이지부터, 아니면 1페이지부터
    @Override
    public void open(ExecutionContext executionContext) {
        reset();
        rewinds = 0;
        nextPage = executionContext.getInt(NEXT_PAGE_KEY, 1);
        totalCount = executionContext.getInt(totalCountKey, -1);
        readCount = (nextPage - 1) * pageSize;
        // 마지막 페이지를 저장한 직후 꺼진 경우. 마지막 페이지는 pageSize보다 짧아서 전체 건수를 넘지 않게 맞춤
        if (totalCount >= 0) {
            readCount = Math.min(readCount, totalCount);
        }
    }

    // 묶음(chunk)을 저장할 때마다 호출됨. 다 읽은 페이지 다음 번호를 저장
    // 버퍼에 남은 item이 있으면 그 페이지를 다시 받도록 한 페이지 앞을 저장 (upsert라 중복 저장돼도 괜찮음)
    @Override
    public void update(ExecutionContext executionContext) {
        executionContext.putInt(NEXT_PAGE_KEY, buffer.isEmpty() ? nextPage : nextPage - 1);
        executionContext.putInt(totalCountKey, totalCount);
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
        // 전체 건수가 바뀌면 행이 밀려 놓칠 수 있음 → 1페이지부터 다시 읽음 (upsert라 중복 저장돼도 괜찮음)
        if (totalCount >= 0 && page.totalCount() != totalCount) {
            rewind(page.totalCount());
            fetchNextPage();
            return;
        }
        totalCount = page.totalCount();
        buffer.addAll(page.items());
        nextPage++;
    }

    private void rewind(int newTotalCount) {
        if (++rewinds > MAX_REWINDS) {
            throw new IllegalStateException(path + " 읽는 동안 전체 건수가 계속 바뀜: "
                    + totalCount + " → " + newTotalCount);
        }
        log.warn("{} 전체 건수가 바뀌어 1페이지부터 다시 읽음: {} → {} ({}/{})",
                path, totalCount, newTotalCount, rewinds, MAX_REWINDS);
        reset();
    }

    // 전체 건수만큼 다 읽었는지 확인. 페이지가 비면 실패 처리
    private void checkAllRead() {
        if (totalCount >= 0 && readCount != totalCount) {
            throw new IllegalStateException(path + " 읽은 건수와 전체 건수가 다름: "
                    + readCount + " / " + totalCount);
        }
    }
}

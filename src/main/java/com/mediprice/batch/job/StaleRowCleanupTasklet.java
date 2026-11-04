package com.mediprice.batch.job;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import com.mediprice.batch.HiraBatchRunner;

// 이번 실행과 직전 성공 실행에서 모두 갱신되지 않은 행(HIRA에서 사라진 데이터)을 삭제
// 앞 Step들이 전부 성공해도 읽는 사이 목록이 바뀌면 멀쩡한 행을 놓칠 수 있어서, 한 번 안 보인 행은 다음 실행까지 남겨둠
public class StaleRowCleanupTasklet implements Tasklet {

    private static final Logger log = LoggerFactory.getLogger(StaleRowCleanupTasklet.class);

    // 이 컴퓨터와 DB 서버의 시계 차이 여유. 주 1회 배치라 1시간 여유를 둬도 옛 행은 충분히 걸러짐
    private static final long CLOCK_MARGIN_HOURS = 1;

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final JobRepository jobRepository;
    private final List<String> tables; // FK 순서대로 (참조하는 테이블 먼저)
    private final Map<String, String> deleteGuards; // 테이블별 추가 삭제 조건. 참조 중인 행을 남길 때 씀

    public StaleRowCleanupTasklet(NamedParameterJdbcTemplate jdbcTemplate, JobRepository jobRepository, List<String> tables, Map<String, String> deleteGuards) {
        this.jdbcTemplate = jdbcTemplate;
        this.jobRepository = jobRepository;
        this.tables = tables;
        this.deleteGuards = deleteGuards;
    }

    // 페이지로 읽은 테이블의 전체 건수 저장 키. Job 실행 기록에 있는 테이블만 건수를 비교함
    public static String totalCountKey(String table) {
        return table + ".totalCount";
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        LocalDateTime requestedAt = chunkContext.getStepContext().getStepExecution()
                .getJobParameters().getLocalDateTime(HiraBatchRunner.REQUESTED_AT);
        if (requestedAt == null) {
            throw new IllegalStateException("실행 파라미터 requestedAt이 없음 (HiraBatchRunner로 실행해야 함)");
        }
        OffsetDateTime cutoff = requestedAt.atZone(ZoneId.systemDefault()).toOffsetDateTime()
                .minusHours(CLOCK_MARGIN_HOURS);
        MapSqlParameterSource params = new MapSqlParameterSource("cutoff", cutoff);

        JobExecution jobExecution = chunkContext.getStepContext().getStepExecution().getJobExecution();
        LocalDateTime lastSuccess = lastSuccessRequestedAt(jobExecution.getJobInstance());
        if (lastSuccess != null) {
            params.addValue("deleteCutoff", lastSuccess.atZone(ZoneId.systemDefault()).toOffsetDateTime()
                    .minusHours(CLOCK_MARGIN_HOURS));
        }

        long totalDeleted = 0;
        for (String table : tables) {
            Integer fresh = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM " + table + " WHERE synced_at >= :cutoff", params, Integer.class);
            // 갱신된 행이 하나도 없으면 다 지워지는 상황이라 이상함 → 건너뜀
            if (fresh == null || fresh == 0) {
                log.warn("{}: 이번 실행에서 갱신된 행이 없어 삭제를 건너뜀", table);
                continue;
            }
            checkFreshCount(jobExecution.getExecutionContext(), table, fresh);
            // 직전 성공 실행이 없으면 두 번 연속 안 보였는지 알 수 없음 → 건너뜀
            if (lastSuccess == null) {
                log.warn("{}: 직전 성공 실행이 없어 삭제를 건너뜀 (갱신된 행 {}건)", table, fresh);
                continue;
            }
            int deleted = jdbcTemplate.update("DELETE FROM " + table + " WHERE synced_at < :deleteCutoff AND " + deleteGuards.getOrDefault(table, "TRUE"), params);
            log.info("{}: 오래된 행 {}건 삭제 (갱신된 행 {}건)", table, deleted, fresh);
            totalDeleted += deleted;
            // 지우고도 남은 오래된 행 = 다른 테이블이 아직 참조 중 (HIRA 목록끼리 어긋남)
            Integer kept = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM " + table + " WHERE synced_at < :deleteCutoff", params, Integer.class);
            if (kept != null && kept > 0) {
                log.warn("{}: 다른 테이블이 참조 중이라 {}건을 남김", table, kept);
            }
        }

        contribution.incrementWriteCount(totalDeleted);
        return RepeatStatus.FINISHED;
    }

    // 갱신된 행이 전체 건수보다 적으면 읽는 중 같은 행을 두 번 읽고 다른 행을 놓친 것 → 실패 (이 Step의 삭제도 전부 되돌아감)
    // 완료된 Step은 --restart로 다시 안 읽으므로 새로 실행해야 함
    private void checkFreshCount(ExecutionContext jobContext, String table, int fresh) {
        String key = totalCountKey(table);
        if (jobContext.containsKey(key) && fresh < jobContext.getInt(key)) {
            throw new IllegalStateException(table + " 갱신된 행이 전체 건수보다 적음: " + fresh + " / "
                    + jobContext.getInt(key) + " (--restart 말고 새로 실행해야 함)");
        }
    }

    // 같은 Job에서 직전에 성공한 실행의 기준 시각. 없으면 null
    // 이어서 실행했으면 저장이 처음 실행 때 시작됐으므로 실행 시작 시각 대신 requestedAt을 씀
    private LocalDateTime lastSuccessRequestedAt(JobInstance current) {
        // 최신 실행부터 나옴
        for (JobInstance instance : jobRepository.getJobInstances(current.getJobName(), 0, Integer.MAX_VALUE)) {
            if (instance.getInstanceId() >= current.getInstanceId()) {
                continue;
            }
            JobExecution last = jobRepository.getLastJobExecution(instance);
            if (last != null && last.getStatus() == BatchStatus.COMPLETED) {
                return last.getJobParameters().getLocalDateTime(HiraBatchRunner.REQUESTED_AT);
            }
        }
        return null;
    }
}

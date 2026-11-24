package com.mediprice.batch.job;

import java.util.List;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

// 배치가 쓴 큰 테이블을 마지막에 직접 VACUUM (ANALYZE)
// Neon은 compute가 다시 시작되면 통계 카운터(n_dead_tup, n_mod_since_analyze)가 0으로 돌아가
// autovacuum·autoanalyze가 이번 실행의 갱신·삭제를 모르고 지나칠 수 있음 (2026-11-22 dev에서 n_live_tup=0, last_autovacuum 없음 실측)
// 그러면 죽은 행이 쌓이고 visibility map이 비어 index-only scan(가격 커버링 인덱스 등)도 heap을 다시 읽음
// VACUUM은 트랜잭션 안에서 실행할 수 없어서 이 Step은 ResourcelessTransactionManager로 등록 (자동 커밋 연결로 실행)
// 데이터는 앞 Step에서 이미 저장됐으므로 실패해도 경고만 남기고 Step은 성공 (다음 실행이나 autovacuum이 이어받음)
public class VacuumTasklet implements Tasklet {

    private static final Logger log = LoggerFactory.getLogger(VacuumTasklet.class);

    // PostgreSQL 17부터 소유자가 아니어도 MAINTAIN 권한으로 VACUUM할 수 있음. 16 이하는 소유자(또는 슈퍼유저)만
    private static final String CAN_VACUUM_PG17_SQL = "SELECT has_table_privilege(?, 'MAINTAIN')";
    private static final String CAN_VACUUM_PG16_SQL =
            "SELECT pg_has_role((SELECT relowner FROM pg_class WHERE oid = to_regclass(?)), 'USAGE')";

    private final JdbcTemplate jdbcTemplate;
    private final List<String> tables;

    public VacuumTasklet(DataSource dataSource, List<String> tables) {
        // 이 Step 전용. 권한 부족·잠금 때문에 건너뛴 VACUUM은 오류가 아니라 WARNING만 와서, 경고를 예외로 받아 "완료"로 찍지 않게 함
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.jdbcTemplate.setIgnoreWarnings(false);
        this.tables = tables;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        for (String table : tables) {
            try {
                if (!canVacuum(table)) {
                    log.warn("{}: VACUUM 권한이 없어 건너뜀 (테이블 소유자이거나 MAINTAIN 권한이 있어야 함)", table);
                    continue;
                }
                long started = System.nanoTime();
                // SKIP_LOCKED: 다른 VACUUM이 잡고 있으면 기다리지 않고 건너뜀 (WARNING → 아래 catch에서 경고 로그)
                jdbcTemplate.execute("VACUUM (ANALYZE, SKIP_LOCKED) " + table);
                log.info("{}: VACUUM (ANALYZE) 완료 ({} ms)", table, (System.nanoTime() - started) / 1_000_000);
            } catch (DataAccessException e) {
                log.warn("{}: VACUUM (ANALYZE) 실패, 배치는 성공으로 둠: {}", table, e.getMostSpecificCause().getMessage());
            }
        }
        return RepeatStatus.FINISHED;
    }

    private boolean canVacuum(String table) {
        Integer version = jdbcTemplate.queryForObject("SELECT current_setting('server_version_num')::int", Integer.class);
        String sql = version != null && version >= 170000 ? CAN_VACUUM_PG17_SQL : CAN_VACUUM_PG16_SQL;
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(sql, Boolean.class, table));
    }
}

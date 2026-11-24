package com.mediprice.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** 실행기를 mock하지 않고 실제 생성자 주입으로 빈이 만들어지는지 확인한다. 컨텍스트 러너는 ApplicationRunner를 실행하지 않는다. */
class HiraBatchRunnerWiringTest {

    @Test
    void 실행기_빈이_Clock_빈과_함께_실제로_만들어짐() {
        new ApplicationContextRunner()
                .withBean(JobOperator.class, () -> mock(JobOperator.class))
                .withBean(JobRepository.class, () -> mock(JobRepository.class))
                .withBean("testJob", Job.class, () -> mock(Job.class))
                .withUserConfiguration(KoreaTimeConfig.class, HiraBatchRunner.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(HiraBatchRunner.class);
                });
    }
}

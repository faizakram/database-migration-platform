package com.migration.platform.monitoring;

import com.migration.platform.job.JobRepository;
import com.migration.platform.job.JobStatus;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Custom application metrics exported via Micrometer → Prometheus at /actuator/prometheus.
 * Seeds the monitoring epic (#50) with a first business metric.
 */
@Component
public class PlatformMetrics {

    private static final List<JobStatus> ACTIVE = List.of(JobStatus.RUNNING, JobStatus.SNAPSHOT);

    public PlatformMetrics(MeterRegistry registry, JobRepository jobs) {
        Gauge.builder("migration_active_jobs", () -> jobs.countByStatusIn(ACTIVE))
                .description("Number of migration jobs currently running or snapshotting")
                .register(registry);
    }
}

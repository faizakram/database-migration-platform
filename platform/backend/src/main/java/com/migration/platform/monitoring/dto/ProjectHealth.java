package com.migration.platform.monitoring.dto;

import java.util.List;
import java.util.UUID;

public record ProjectHealth(
        UUID projectId,
        String projectName,
        UUID jobId,
        String jobStatus,
        boolean healthy,
        List<ConnectorHealth> connectors
) {}

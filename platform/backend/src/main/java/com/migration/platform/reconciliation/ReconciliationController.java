package com.migration.platform.reconciliation;

import com.migration.platform.reconciliation.dto.ReconciliationDtos.RunDto;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/reconciliation")
public class ReconciliationController {

    private final ReconciliationService service;

    public ReconciliationController(ReconciliationService service) {
        this.service = service;
    }

    /** Trigger a reconciliation run (synchronous). mode = COUNT (default) | CHECKSUM. */
    @PostMapping
    public RunDto run(@PathVariable UUID projectId,
                      @RequestParam(defaultValue = "COUNT") String mode,
                      @RequestParam(defaultValue = "1000") int sampleSize) {
        return service.run(projectId, mode, sampleSize);
    }

    /** Past runs with their per-table results. */
    @GetMapping
    public List<RunDto> history(@PathVariable UUID projectId) {
        return service.history(projectId);
    }
}

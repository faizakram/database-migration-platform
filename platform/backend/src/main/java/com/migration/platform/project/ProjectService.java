package com.migration.platform.project;

import com.migration.platform.common.NotFoundException;
import com.migration.platform.project.dto.ProjectRequest;
import com.migration.platform.project.dto.ProjectResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.UUID;

@Service
public class ProjectService {

    private final ProjectRepository repo;

    public ProjectService(ProjectRepository repo) {
        this.repo = repo;
    }

    @Transactional(readOnly = true)
    public List<ProjectResponse> list() {
        return repo.findAll().stream().map(ProjectResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public ProjectResponse get(UUID id) {
        return ProjectResponse.from(find(id));
    }

    @Transactional
    public ProjectResponse create(ProjectRequest req) {
        if (repo.existsByName(req.name())) {
            throw new IllegalArgumentException("A project named '" + req.name() + "' already exists");
        }
        MigrationProject p = new MigrationProject();
        apply(p, req);
        return ProjectResponse.from(repo.save(p));
    }

    @Transactional
    public ProjectResponse update(UUID id, ProjectRequest req) {
        MigrationProject p = find(id);
        apply(p, req);
        return ProjectResponse.from(repo.save(p));
    }

    @Transactional
    public void delete(UUID id) {
        if (!repo.existsById(id)) throw new NotFoundException("Project " + id + " not found");
        repo.deleteById(id);
    }

    private void apply(MigrationProject p, ProjectRequest req) {
        p.setName(req.name());
        p.setDescription(req.description());
        p.setSourceConnectionId(req.sourceConnectionId());
        p.setTargetConnectionId(req.targetConnectionId());
        p.setConfig(req.config() != null ? req.config() : new HashMap<>());
        // A project is READY once both endpoints are chosen.
        if (p.getSourceConnectionId() != null && p.getTargetConnectionId() != null
                && p.getStatus() == ProjectStatus.DRAFT) {
            p.setStatus(ProjectStatus.READY);
        }
    }

    private MigrationProject find(UUID id) {
        return repo.findById(id).orElseThrow(() -> new NotFoundException("Project " + id + " not found"));
    }
}

package com.migration.platform.project;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ProjectRepository extends JpaRepository<MigrationProject, UUID> {
    boolean existsByName(String name);
}

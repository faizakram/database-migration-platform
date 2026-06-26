package com.migration.platform.connection;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ConnectionRepository extends JpaRepository<DbConnection, UUID> {
    boolean existsByName(String name);
}

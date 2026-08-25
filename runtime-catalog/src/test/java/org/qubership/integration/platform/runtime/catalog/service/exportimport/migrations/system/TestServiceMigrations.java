package org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.system;

import org.qubership.integration.platform.io.readers.migrations.system.ServiceImportFileMigration;
import org.qubership.integration.platform.io.readers.migrations.system.V100ServiceImportFileMigration;
import org.qubership.integration.platform.io.readers.migrations.system.V101ServiceImportFileMigration;
import org.qubership.integration.platform.io.readers.migrations.system.V102ServiceImportFileMigration;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.services.ApiOperationDtoMapper;

import java.util.List;

/**
 * The service import migrations Spring injects into {@code ServiceDeserializer}. Every test that builds a deserializer
 * takes its list from here, so adding a migration updates them all at once instead of leaving each literal to drift.
 */
public final class TestServiceMigrations {

    private TestServiceMigrations() {
    }

    public static List<ServiceImportFileMigration> all() {
        return List.of(
                new V100ServiceImportFileMigration(),
                new V101ServiceImportFileMigration(),
                new V102ServiceImportFileMigration(),
                new V103ServiceImportFileMigration(new ApiOperationDtoMapper()),
                new V104ServiceImportFileMigration(),
                new V105ServiceImportFileMigration());
    }
}

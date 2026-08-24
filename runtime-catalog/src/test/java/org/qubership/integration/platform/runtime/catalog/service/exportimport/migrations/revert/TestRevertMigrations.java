package org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.revert;

import org.qubership.integration.platform.io.readers.migrations.revert.RevertMigration;
import org.qubership.integration.platform.io.readers.migrations.revert.V101RevertMigration;
import org.qubership.integration.platform.io.readers.migrations.revert.V108RevertMigration;
import org.qubership.integration.platform.runtime.catalog.configuration.ApplicationJsonSchemaProperties;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.ServiceTypeFiles;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.services.ApiOperationDtoMapper;

import java.net.URI;
import java.util.List;

/**
 * The revert migrations Spring injects into {@code FileMigrationService}. Every legacy-export test builds its list
 * from here, so adding a revert migration updates them all at once instead of leaving each literal to drift.
 */
public final class TestRevertMigrations {

    private TestRevertMigrations() {
    }

    public static List<RevertMigration> all(URI specificationSchemaUri) {
        ApplicationJsonSchemaProperties schemas = new ApplicationJsonSchemaProperties();
        ServiceDocumentMatcher serviceDocumentMatcher = new ServiceDocumentMatcher(schemas);
        return List.of(
                new V101RevertMigration(),
                new V103RevertMigration(new ApiOperationDtoMapper(), specificationSchemaUri, serviceDocumentMatcher),
                new V104RevertMigration(serviceDocumentMatcher),
                new V105RevertMigration(serviceDocumentMatcher, new ServiceTypeFiles(schemas), schemas),
                new V108RevertMigration());
    }

    /** The matcher Spring builds: {@link ApplicationJsonSchemaProperties} defaults are the shipped schema URIs. */
    public static ServiceDocumentMatcher matcher() {
        return new ServiceDocumentMatcher(new ApplicationJsonSchemaProperties());
    }
}

package org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.io.readers.migrations.FileMigrationService;
import org.qubership.integration.platform.io.readers.migrations.revert.RevertMigration;
import org.qubership.integration.platform.io.readers.migrations.versions.VersionsGetterService;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FileMigrationServiceTest {

    @Mock
    private YAMLMapper yamlMapper;
    @Mock
    private VersionsGetterService versionsGetterService;

    private final JsonNodeFactory factory = JsonNodeFactory.instance;

    private FileMigrationService service(boolean legacy, List<RevertMigration> migrations) {
        FileMigrationService fileMigrationService =
                new FileMigrationService(yamlMapper, versionsGetterService, migrations);
        ReflectionTestUtils.setField(fileMigrationService, "isLegacyExport", legacy);
        return fileMigrationService;
    }

    @DisplayName("Should return null for a null node")
    @Test
    void shouldReturnNullForNullNode() {
        assertNull(service(true, List.of()).revertMigrationIfNeeded(null));
    }

    @DisplayName("Should not apply revert migrations when legacy export is off")
    @Test
    void shouldPassThroughWhenNotLegacy() {
        ObjectNode node = factory.objectNode();
        RevertMigration migration = mock(RevertMigration.class);

        assertSame(node, service(false, List.of(migration)).revertMigrationIfNeeded(node));
        verify(migration, never()).revert(any());
    }

    @DisplayName("Should apply supported revert migrations in descending version order")
    @Test
    void shouldApplySupportedMigrationsInDescendingOrder() {
        ObjectNode input = factory.objectNode();
        ObjectNode after108 = factory.objectNode();
        ObjectNode after101 = factory.objectNode();

        RevertMigration migration101 = mock(RevertMigration.class);
        RevertMigration migration108 = mock(RevertMigration.class);
        when(migration101.getVersion()).thenReturn(101);
        when(migration108.getVersion()).thenReturn(108);
        when(migration108.supportsDocument(input)).thenReturn(true);
        when(migration108.revert(input)).thenReturn(after108);
        when(migration101.supportsDocument(after108)).thenReturn(true);
        when(migration101.revert(after108)).thenReturn(after101);

        // Constructor receives them in ascending order; they must run 108 -> 101.
        ObjectNode result = service(true, List.of(migration101, migration108)).revertMigrationIfNeeded(input);

        assertSame(after101, result);
        InOrder order = inOrder(migration108, migration101);
        order.verify(migration108).revert(input);
        order.verify(migration101).revert(after108);
    }

    @DisplayName("Should skip a revert migration that does not support the document")
    @Test
    void shouldSkipUnsupportedMigration() {
        ObjectNode input = factory.objectNode();
        RevertMigration migration = mock(RevertMigration.class);
        when(migration.supportsDocument(input)).thenReturn(false);

        assertSame(input, service(true, List.of(migration)).revertMigrationIfNeeded(input));
        verify(migration, never()).revert(any());
    }
}

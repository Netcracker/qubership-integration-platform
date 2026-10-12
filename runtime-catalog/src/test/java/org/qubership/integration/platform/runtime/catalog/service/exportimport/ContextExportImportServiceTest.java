package org.qubership.integration.platform.runtime.catalog.service.exportimport;

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.runtime.catalog.configuration.ApplicationJsonSchemaProperties;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.instructions.ImportInstructionsConfig;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.system.ImportSystemResult;
import org.qubership.integration.platform.runtime.catalog.service.ActionsLogService;
import org.qubership.integration.platform.runtime.catalog.service.ContextBaseService;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.deserializer.ContextServiceDeserializer;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.instructions.ImportInstructionsService;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.serializer.ArchiveWriter;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.serializer.ContextServiceSerializer;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith(MockitoExtension.class)
class ContextExportImportServiceTest {

    private static final String SYSTEM_ID = "context-service-1";

    @Mock TransactionTemplate transactionTemplate;
    @Mock ContextBaseService contextBaseService;
    @Mock ActionsLogService actionLogger;
    @Mock ContextServiceSerializer contextServiceSerializer;
    @Mock ContextServiceDeserializer contextServiceDeserializer;
    @Mock ArchiveWriter archiveWriter;
    @Mock ImportSessionService importProgressService;
    @Mock ImportInstructionsService importInstructionsService;

    @ParameterizedTest
    @CsvSource({
            "cip, , http://netcracker.com/schemas/product/cloud-integration-platform/conf-model/context-service, true",
            "cip, , http://qubership.org/schemas/product/qip/context-service, true",
            "qip, , http://qubership.org/schemas/product/qip/context-service, true",
            "cip, , http://unknown.schema/context-service, false",
            "cip, http://custom/context-service, http://custom/context-service, true",
            "cip, http://custom/context-service, http://qubership.org/schemas/product/qip/context-service, true",
            "cip, http://custom/context-service, "
                    + "http://netcracker.com/schemas/product/cloud-integration-platform/conf-model/context-service, false"
    })
    @DisplayName("Import preview reads context service files with the configured or the legacy schema and skips others")
    void importPreviewFiltersContextServiceFilesBySchema(
            String appName,
            String configuredSchema,
            String schema,
            boolean imported,
            @TempDir Path importDirectory
    ) throws Exception {
        Path serviceDirectory = Files.createDirectories(importDirectory.resolve("services").resolve(SYSTEM_ID));
        Files.writeString(serviceDirectory.resolve(SYSTEM_ID + ".context-service." + appName + ".yaml"),
                "$schema: " + schema + "\nid: " + SYSTEM_ID + "\nname: Context service\n");
        ApplicationJsonSchemaProperties schemaProperties = new ApplicationJsonSchemaProperties();
        if (configuredSchema != null) {
            schemaProperties.setContextService(configuredSchema);
        }
        ContextExportImportService service = new ContextExportImportService(
                transactionTemplate,
                contextBaseService,
                new YAMLMapper(),
                actionLogger,
                contextServiceSerializer,
                contextServiceDeserializer,
                archiveWriter,
                importProgressService,
                importInstructionsService,
                schemaProperties
        );

        List<ImportSystemResult> result = service.getContextServiceImportPreview(
                importDirectory.toFile(), ImportInstructionsConfig.builder().build());

        assertEquals(imported ? List.of(SYSTEM_ID) : List.of(),
                result.stream().map(ImportSystemResult::getId).toList());
    }
}

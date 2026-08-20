package org.qubership.integration.platform.runtime.catalog.service.parsers;

import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.runtime.catalog.model.system.OperationProtocol;
import org.qubership.integration.platform.runtime.catalog.persistence.TransactionHandler;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.ApiGroup;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.IntegrationSystem;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.Operation;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SpecificationSource;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SystemModel;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.operations.OperationRepository;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.system.ApiGroupRepository;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.system.SpecificationSourceRepository;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.system.SystemModelRepository;
import org.qubership.integration.platform.runtime.catalog.service.ActionsLogService;
import org.qubership.integration.platform.runtime.catalog.service.extractor.OperationSchemaExtractor;
import org.qubership.integration.platform.runtime.catalog.service.extractor.OperationSchemaExtractor.OperationKey;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OperationParserServiceTest {

    @Test
    void parseWritesSpecificationTypeAndVersionIntoTheirOwnFields() throws Exception {
        // specificationType and specificationVersion are adjacent String parameters; a swap would silently land
        // the version in the type field. Distinct values make such a swap fail the assertions.
        String specificationType = "openapi";
        String specificationVersion = "3.0.1";

        SystemModel result = parseWith(new Harness(), specificationType, specificationVersion, message -> { });

        assertEquals(specificationType, result.getSpecificationType());
        assertEquals(specificationVersion, result.getSpecificationVersion());
    }

    // --- the post-import extraction check: schemas the read path cannot rebuild warn here, not at first read ---

    @Test
    void aSourceWhoseSchemasCannotBeRebuiltWarnsAndStillImports() throws Exception {
        Harness harness = new Harness().withSystemProtocol(OperationProtocol.HTTP);
        when(harness.schemaExtractor.extractAll(anyList(), any(), anyBoolean()))
                .thenThrow(new RuntimeException("Unable to resolve schema components"));
        List<String> messages = new ArrayList<>();

        SystemModel result = parseWith(harness, "openapi", "3.0.1", messages::add);

        assertEquals(harness.model, result);
        assertEquals(1, messages.size());
        assertTrue(messages.get(0).contains("Unable to resolve schema components"), messages::toString);
        assertTrue(messages.get(0).contains("were imported"), messages::toString);
    }

    @Test
    void anOperationTheExtractionCannotMatchIsNamedInTheWarning() throws Exception {
        Harness harness = new Harness().withSystemProtocol(OperationProtocol.HTTP);
        harness.model.addProvidedOperation(operation("/orders", "get"));
        when(harness.schemaExtractor.extractAll(anyList(), any(), anyBoolean())).thenReturn(Map.of());
        List<String> messages = new ArrayList<>();

        parseWith(harness, "openapi", "3.0.1", messages::add);

        assertEquals(1, messages.size());
        assertTrue(messages.get(0).contains("/orders"), messages::toString);
    }

    @Test
    void aCleanExtractionWarnsNothing() throws Exception {
        Harness harness = new Harness().withSystemProtocol(OperationProtocol.HTTP);
        harness.model.addProvidedOperation(operation("/orders", "get"));
        when(harness.schemaExtractor.extractAll(anyList(), any(), anyBoolean()))
                .thenReturn(Map.of(OperationKey.of("/orders", "get"),
                        new OperationSchemaExtractor.ExtractedSchemas(null, null, null)));
        List<String> messages = new ArrayList<>();

        parseWith(harness, "openapi", "3.0.1", messages::add);

        assertEquals(List.of(), messages);
    }

    @Test
    void aProtocolWithNoExtractionPathIsNotValidated() throws Exception {
        Harness harness = new Harness().withSystemProtocol(OperationProtocol.SOAP);
        List<String> messages = new ArrayList<>();

        parseWith(harness, "soap", "1.1", messages::add);

        verify(harness.schemaExtractor, never()).extractAll(anyList(), any(), anyBoolean());
        assertEquals(List.of(), messages);
    }

    private static Operation operation(String path, String method) {
        Operation operation = new Operation();
        operation.setPath(path);
        operation.setMethod(method);
        return operation;
    }

    private static SystemModel parseWith(Harness harness,
                                         String specificationType,
                                         String specificationVersion,
                                         Consumer<String> messageHandler) throws Exception {
        return harness.service().parse(
                "test",
                "group-1",
                Collections.emptyList(),
                false,
                Collections.emptySet(),
                specificationType,
                specificationVersion,
                messageHandler).get();
    }

    /** The mocks one {@code parse} run needs, with the model the fake parser hands back exposed for seeding. */
    private static final class Harness {
        final SystemModel model = new SystemModel();
        final OperationSchemaExtractor schemaExtractor = mock(OperationSchemaExtractor.class);
        final ApiGroupRepository apiGroupRepository = mock(ApiGroupRepository.class);

        Harness withSystemProtocol(OperationProtocol protocol) {
            IntegrationSystem system = new IntegrationSystem();
            system.setProtocol(protocol);
            ApiGroup group = new ApiGroup();
            group.setSystem(system);
            when(apiGroupRepository.getReferenceById(any())).thenReturn(group);
            return this;
        }

        OperationParserService service() {
            SystemModelRepository systemModelRepository = mock(SystemModelRepository.class);
            when(systemModelRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

            TransactionHandler transactionHandler = mock(TransactionHandler.class);
            when(transactionHandler.supplyInNewTransaction(any()))
                    .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(0)).get());

            return new OperationParserService(
                    List.of(new FakeParser(model)),
                    mock(OperationRepository.class),
                    systemModelRepository,
                    apiGroupRepository,
                    mock(SpecificationSourceRepository.class),
                    mock(ActionsLogService.class),
                    transactionHandler,
                    schemaExtractor);
        }
    }

    // Registered by its @Parser value; enrichSpecificationGroup returns a bare model for parse() to populate.
    @Parser("test")
    private static final class FakeParser implements SpecificationParser {
        private final SystemModel model;

        private FakeParser(SystemModel model) {
            this.model = model;
        }

        @Override
        public SystemModel enrichSpecificationGroup(ApiGroup group,
                                                    Collection<SpecificationSource> sources,
                                                    Set<String> oldSystemModelsIds,
                                                    boolean isDiscovered,
                                                    boolean withSchemas,
                                                    Consumer<String> messageHandler) {
            return model;
        }
    }
}

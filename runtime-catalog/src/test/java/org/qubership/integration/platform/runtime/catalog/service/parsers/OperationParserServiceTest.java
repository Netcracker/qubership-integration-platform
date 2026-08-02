package org.qubership.integration.platform.runtime.catalog.service.parsers;

import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.runtime.catalog.persistence.TransactionHandler;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.ApiGroup;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SpecificationSource;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SystemModel;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.operations.OperationRepository;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.system.ApiGroupRepository;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.system.SpecificationSourceRepository;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.system.SystemModelRepository;
import org.qubership.integration.platform.runtime.catalog.service.ActionsLogService;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OperationParserServiceTest {

    @Test
    void parseWritesSpecificationTypeAndVersionIntoTheirOwnFields() throws Exception {
        // specificationType and specificationVersion are adjacent String parameters; a swap would silently land
        // the version in the type field. Distinct values make such a swap fail the assertions.
        String specificationType = "openapi";
        String specificationVersion = "3.0.1";

        SystemModelRepository systemModelRepository = mock(SystemModelRepository.class);
        when(systemModelRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        TransactionHandler transactionHandler = mock(TransactionHandler.class);
        when(transactionHandler.supplyInNewTransaction(any()))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(0)).get());

        OperationParserService service = new OperationParserService(
                List.of(new FakeParser()),
                mock(OperationRepository.class),
                systemModelRepository,
                mock(ApiGroupRepository.class),
                mock(SpecificationSourceRepository.class),
                mock(ActionsLogService.class),
                transactionHandler);

        SystemModel result = service.parse(
                "test",
                "group-1",
                Collections.emptyList(),
                false,
                Collections.emptySet(),
                specificationType,
                specificationVersion,
                message -> { }).get();

        assertEquals(specificationType, result.getSpecificationType());
        assertEquals(specificationVersion, result.getSpecificationVersion());
    }

    // Registered by its @Parser value; enrichSpecificationGroup returns a bare model for parse() to populate.
    @Parser("test")
    private static final class FakeParser implements SpecificationParser {
        @Override
        public SystemModel enrichSpecificationGroup(ApiGroup group,
                                                    Collection<SpecificationSource> sources,
                                                    Set<String> oldSystemModelsIds,
                                                    boolean isDiscovered,
                                                    boolean withSchemas,
                                                    Consumer<String> messageHandler) {
            return new SystemModel();
        }
    }
}

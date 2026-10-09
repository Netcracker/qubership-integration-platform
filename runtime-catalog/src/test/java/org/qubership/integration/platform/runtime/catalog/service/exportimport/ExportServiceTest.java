package org.qubership.integration.platform.runtime.catalog.service.exportimport;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.io.model.exportimport.chain.ChainExternalEntity;
import org.qubership.integration.platform.io.readers.migrations.FileMigrationService;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.chain.ChainExternalMapperEntity;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Chain;
import org.qubership.integration.platform.runtime.catalog.service.ActionsLogService;
import org.qubership.integration.platform.runtime.catalog.service.ChainService;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.chain.ChainExternalEntityMapper;
import org.qubership.integration.platform.runtime.catalog.service.helpers.ChainFinderService;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExportServiceTest {

    @Mock
    private YAMLMapper yamlMapper;
    @Mock
    private ChainService chainService;
    @Mock
    private ChainFinderService chainFinderService;
    @Mock
    private ActionsLogService actionLogger;
    @Mock
    private ChainExternalEntityMapper chainExternalEntityMapper;
    @Mock
    private FileMigrationService fileMigrationService;

    @InjectMocks
    private ExportService service;

    private void stubExport(Chain chain) throws Exception {
        when(chainFinderService.findById("c1")).thenReturn(chain);
        ChainExternalEntity external = ChainExternalEntity.builder()
                .id("c1")
                .name("chain")
                .build();
        when(chainExternalEntityMapper.toExternalEntity(chain)).thenReturn(ChainExternalMapperEntity.builder()
                .chainExternalEntity(external)
                .elementPropertyFiles(Map.of())
                .build());
        when(fileMigrationService.revertMigrationIfNeeded(any())).thenReturn(JsonNodeFactory.instance.objectNode());
        when(yamlMapper.valueToTree(any())).thenReturn(JsonNodeFactory.instance.objectNode());
        when(yamlMapper.writeValueAsString(any())).thenReturn("yaml-content");
    }

    @Test
    void exportBackfillsHashWhenNull() throws Exception {
        Chain chain = Chain.builder().id("c1").name("chain").build();
        chain.setLastImportHash(null);
        stubExport(chain);

        service.exportSingleChain("c1");

        verify(chainService).backfillLastImportHash(eq("c1"), anyString());
    }

    @Test
    void exportBackfillsHashWhenZero() throws Exception {
        Chain chain = Chain.builder().id("c1").name("chain").build();
        chain.setLastImportHash("0");
        stubExport(chain);

        service.exportSingleChain("c1");

        verify(chainService).backfillLastImportHash(eq("c1"), anyString());
    }

    @Test
    void exportDoesNotBackfillHashWhenPresent() throws Exception {
        Chain chain = Chain.builder().id("c1").name("chain").build();
        chain.setLastImportHash("existing-hash");
        stubExport(chain);

        service.exportSingleChain("c1");

        verify(chainService, never()).backfillLastImportHash(anyString(), anyString());
    }
}

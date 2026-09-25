package org.qubership.integration.platform.runtime.catalog.service.ddsgenerator.elements;

import com.vladsch.flexmark.formatter.Formatter;
import com.vladsch.flexmark.parser.Parser;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.runtime.catalog.model.dds.TemplateChain;
import org.qubership.integration.platform.runtime.catalog.model.dds.TemplateChainDoc;
import org.qubership.integration.platform.runtime.catalog.model.dds.TemplateData;
import org.qubership.integration.platform.runtime.catalog.model.dds.TemplateSequenceDiagram;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Chain;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.DetailedDesignTemplate;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.DetailedDesignTemplateRepository;
import org.qubership.integration.platform.runtime.catalog.service.ActionsLogService;
import org.qubership.integration.platform.runtime.catalog.service.OperationService;
import org.qubership.integration.platform.runtime.catalog.service.SystemModelService;
import org.qubership.integration.platform.runtime.catalog.service.helpers.ChainFinderService;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DetailedDesignServiceTest {

    @Mock
    private ChainFinderService chainFinderService;
    @Mock
    private SystemModelService systemModelService;
    @Mock
    private OperationService operationService;
    @Mock
    private ActionsLogService actionsLogService;
    @Mock
    private TemplateDataBuilder templateDataBuilder;
    @Mock
    private DetailedDesignTemplateRepository repository;

    private DetailedDesignService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new DetailedDesignService(chainFinderService, systemModelService, operationService, actionsLogService,
                templateDataBuilder, repository,
                Parser.builder().build(), Formatter.builder().build());
        Chain chain = Chain.builder().elements(List.of()).build();
        when(chainFinderService.findById("chain-1")).thenReturn(chain);
        when(templateDataBuilder.build(any(), any())).thenReturn(TemplateData.builder()
                .chain(TemplateChain.builder()
                        .name("Orders")
                        .doc(TemplateChainDoc.builder().simpleSeqDiagram(new TemplateSequenceDiagram()).build())
                        .build())
                .build());
    }

    @Test
    void buildChainDetailedDesignRendersTheStoredContent() throws Exception {
        stored("custom", "# ${chain.name}\n");

        assertThat(service.buildChainDetailedDesign("chain-1", "custom").getDocument()).startsWith("# Orders");
    }

    @Test
    void buildChainDetailedDesignResolvesAnIncludeFromTheStore() throws Exception {
        stored("outer", "<#include \"part\">");
        stored("part", "# Part\n");

        assertThat(service.buildChainDetailedDesign("chain-1", "outer").getDocument()).startsWith("# Part");
    }

    @Test
    void buildChainDetailedDesignResolvesAnIncludeAgainOnEveryRender() throws Exception {
        stored("outer", "<#include \"part\">");
        stored("part", "# Part\n");
        service.buildChainDetailedDesign("chain-1", "outer");

        stored("part", "# Recreated\n");

        assertThat(service.buildChainDetailedDesign("chain-1", "outer").getDocument()).startsWith("# Recreated");
    }

    @Test
    void buildChainDetailedDesignThrowsNotFoundWhenTheTemplateIsGone() {
        when(repository.findById("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.buildChainDetailedDesign("chain-1", "ghost"))
                .isInstanceOf(EntityNotFoundException.class);
    }

    private void stored(String id, String content) {
        when(repository.findById(id)).thenReturn(Optional.of(
                DetailedDesignTemplate.builder().id(id).name(id).content(content).build()));
    }
}

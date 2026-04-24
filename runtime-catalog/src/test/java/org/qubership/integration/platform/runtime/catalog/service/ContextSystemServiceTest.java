package org.qubership.integration.platform.runtime.catalog.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.AdditionalAnswers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.AbstractLabel;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog.ActionLog;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog.LogOperation;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.context.ContextSystem;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.context.ContextSystemLabel;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.context.ContextSystemLabelsRepository;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.context.ContextSystemRepository;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.system.SystemLabelDTO;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.system.context.ContextSystemUpdateRequestDTO;
import org.qubership.integration.platform.runtime.catalog.rest.v1.mapper.ContextSystemMapper;
import org.qubership.integration.platform.runtime.catalog.service.filter.SystemFilterSpecificationBuilder;
import org.qubership.integration.platform.runtime.catalog.service.helpers.ElementHelperService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContextSystemServiceTest {
    @Mock
    private ContextSystemRepository contextSystemRepository;
    @Mock
    private ActionsLogService actionLogger;
    @Mock
    private ChainService chainService;
    @Mock
    private ElementHelperService elementHelperService;
    @Mock
    private SystemFilterSpecificationBuilder systemFilterSpecificationBuilder;
    @Mock
    private ContextSystemLabelsRepository contextSystemLabelsRepository;
    private ContextSystemMapper contextSystemMapper = Mockito.spy(Mappers.getMapper(ContextSystemMapper.class));

    private ContextSystemService contextSystemService;

    private static final String SYSTEM_ID = "test-system-id";
    private static final String SYSTEM_NAME = "Test System";
    private static final String SYSTEM_DESCRIPTION = "Test Description";
    private static final String LABEL_NAME_1 = "label1";
    private static final String LABEL_NAME_2 = "label2";

    private static final ContextSystem EXISTING_SYSTEM = ContextSystem.builder()
            .id(SYSTEM_ID)
            .name(SYSTEM_NAME)
            .description(SYSTEM_DESCRIPTION)
            .labels(new HashSet<>())
            .build();

    @BeforeEach
    void setUp() {
        contextSystemService = new ContextSystemService(
                contextSystemRepository,
                contextSystemMapper,
                actionLogger,
                systemFilterSpecificationBuilder,
                chainService,
                elementHelperService,
                contextSystemLabelsRepository);
        Mockito.reset(contextSystemRepository, contextSystemLabelsRepository);
    }

    void mockExistingSystem() {
        when(contextSystemRepository.findById(SYSTEM_ID))
                .thenReturn(Optional.of(EXISTING_SYSTEM));
    }

    @Test
    @DisplayName("Should successfully update context system with new labels")
    void updateShouldUpdateSystemAndLabelsWhenSystemWithLabelsPassed() {
        ContextSystemUpdateRequestDTO request = new ContextSystemUpdateRequestDTO();
        request.setName("Updated Name");
        request.setDescription("Updated Description");
        request.setLabels(
                new ArrayList<>(Collections
                        .singletonList(SystemLabelDTO.builder().name(LABEL_NAME_1).technical(false).build())));

        mockExistingSystem();
        doAnswer(AdditionalAnswers.returnsFirstArg()).when(contextSystemRepository).save(any(ContextSystem.class));
        doAnswer(AdditionalAnswers.returnsFirstArg()).when(contextSystemLabelsRepository).saveAll(anyList());

        ContextSystem result = contextSystemService.update(request, SYSTEM_ID);

        verify(contextSystemMapper).mergeWithoutLabels(EXISTING_SYSTEM, request);
        verify(contextSystemMapper).asLabelRequests(request.getLabels());

        argCaptorLogAction(LogOperation.UPDATE, SYSTEM_ID);

        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(SYSTEM_ID);

        assertEquals(request.getName(), result.getName());
        assertEquals(request.getDescription(), result.getDescription());
        assertEquals(request.getLabels().size(), result.getLabels().size());
        assertEquals(request.getLabels().get(0).getName(), result.getLabels().iterator().next().getName());
    }

    @Test
    @DisplayName("Should successfully update context system with null labels")
    void updateShouldUpdateSystemWhenNoLabelsPassed() {
        ContextSystemUpdateRequestDTO request = new ContextSystemUpdateRequestDTO();
        request.setName("Updated Name");
        request.setDescription("Updated Description");
        request.setLabels(null);

        mockExistingSystem();
        doAnswer(AdditionalAnswers.returnsFirstArg()).when(contextSystemRepository).save(any(ContextSystem.class));

        ContextSystem result = contextSystemService.update(request, SYSTEM_ID);

        verify(contextSystemMapper).mergeWithoutLabels(EXISTING_SYSTEM, request);
        verify(contextSystemMapper).asLabelRequests(request.getLabels());
        verify(contextSystemLabelsRepository, never()).saveAll(any());
        verify(contextSystemRepository).save(EXISTING_SYSTEM);

        argCaptorLogAction(LogOperation.UPDATE, SYSTEM_ID);

        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(SYSTEM_ID);

        assertEquals(request.getName(), result.getName());
        assertEquals(request.getDescription(), result.getDescription());
        assertEquals(0, result.getLabels().size());
    }

    @Test
    @DisplayName("Should return early when labels parameter is null")
    void replaceLabelsWithNullLabelsShouldReturnEarly() {
        // Given
        ContextSystem system = ContextSystem.builder()
                .id(SYSTEM_ID)
                .name(SYSTEM_NAME)
                .labels(new java.util.LinkedHashSet<>())
                .build();

        // When
        contextSystemService.replaceLabels(system, null);

        // Then
        verifyNoInteractions(contextSystemLabelsRepository);
        assertThat(system.getLabels()).isEmpty();
    }

    @Test
    @DisplayName("Should set system reference on each label")
    void replaceLabelsShouldSetSystemReferenceOnLabels() {
        // Given
        ContextSystem system = ContextSystem.builder()
                .id(SYSTEM_ID)
                .name(SYSTEM_NAME)
                .labels(new java.util.LinkedHashSet<>())
                .build();

        ContextSystemLabel label1 = ContextSystemLabel.builder()
                .name(LABEL_NAME_1)
                .technical(false)
                .build();
        ContextSystemLabel label2 = ContextSystemLabel.builder()
                .name(LABEL_NAME_2)
                .technical(false)
                .build();
        List<ContextSystemLabel> labels = new ArrayList<>(List.of(label1, label2));

        // When
        contextSystemService.replaceLabels(system, labels);

        // Then
        assertThat(label1.getSystem()).isEqualTo(system);
        assertThat(label2.getSystem()).isEqualTo(system);
    }

    @Test
    @DisplayName("Should remove non-technical labels not present in new list")
    void replaceLabelsShouldRemoveAbsentNonTechnicalLabels() {
        // Given
        ContextSystemLabel existingLabel1 = ContextSystemLabel.builder()
                .name(LABEL_NAME_1)
                .technical(false)
                .build();
        ContextSystemLabel existingLabel2 = ContextSystemLabel.builder()
                .name(LABEL_NAME_2)
                .technical(false)
                .build();
        ContextSystemLabel technicalLabel = ContextSystemLabel.builder()
                .name("technical-label")
                .technical(true)
                .build();

        ContextSystem system = ContextSystem.builder()
                .id(SYSTEM_ID)
                .name(SYSTEM_NAME)
                .labels(new java.util.LinkedHashSet<>(List.of(existingLabel1, existingLabel2, technicalLabel)))
                .build();

        // New labels list contains only label1 (label2 should be removed)
        ContextSystemLabel newLabel1 = ContextSystemLabel.builder()
                .name(LABEL_NAME_1)
                .technical(false)
                .build();
        List<ContextSystemLabel> newLabels = new ArrayList<>(List.of(newLabel1));

        // When
        contextSystemService.replaceLabels(system, newLabels);

        // Then
        assertThat(system.getLabels())
                .extracting(AbstractLabel::getName)
                .containsExactlyInAnyOrder(LABEL_NAME_1, "technical-label");
        // Technical label should be preserved
        assertThat(system.getLabels())
                .filteredOn(AbstractLabel::isTechnical)
                .hasSize(1);
    }

    @Test
    @DisplayName("Should preserve technical labels during replacement")
    void replaceLabelsShouldPreserveTechnicalLabels() {
        // Given
        ContextSystemLabel technicalLabel1 = ContextSystemLabel.builder()
                .name("system-generated-1")
                .technical(true)
                .build();
        ContextSystemLabel technicalLabel2 = ContextSystemLabel.builder()
                .name("system-generated-2")
                .technical(true)
                .build();

        ContextSystem system = ContextSystem.builder()
                .id(SYSTEM_ID)
                .name(SYSTEM_NAME)
                .labels(new java.util.LinkedHashSet<>(List.of(technicalLabel1, technicalLabel2)))
                .build();

        // Request contains only non-technical labels
        ContextSystemLabel userLabel = ContextSystemLabel.builder()
                .name(LABEL_NAME_1)
                .technical(false)
                .build();
        List<ContextSystemLabel> newLabels = new ArrayList<>(List.of(userLabel));

        when(contextSystemLabelsRepository.saveAll(List.of(userLabel)))
                .thenReturn(List.of(userLabel));

        // When
        contextSystemService.replaceLabels(system, newLabels);

        // Then
        // Technical labels should never be removed
        assertThat(system.getLabels())
                .filteredOn(AbstractLabel::isTechnical)
                .extracting(AbstractLabel::getName)
                .containsExactlyInAnyOrder("system-generated-1", "system-generated-2");

        assertThat(system.getLabels())
                .extracting(AbstractLabel::getName)
                .contains(LABEL_NAME_1);
    }

    @Test
    @DisplayName("Should not add technical labels from request")
    void replaceLabelsShouldNotAddTechnicalLabelsFromRequest() {
        // Given
        ContextSystem system = ContextSystem.builder()
                .id(SYSTEM_ID)
                .name(SYSTEM_NAME)
                .labels(new java.util.LinkedHashSet<>())
                .build();

        ContextSystemLabel technicalLabel = ContextSystemLabel.builder()
                .name("should-not-be-added")
                .technical(true)
                .build();
        ContextSystemLabel nonTechnicalLabel = ContextSystemLabel.builder()
                .name(LABEL_NAME_1)
                .technical(false)
                .build();
        List<ContextSystemLabel> labels = new ArrayList<>(List.of(technicalLabel, nonTechnicalLabel));

        // Only non-technical label should be saved
        when(contextSystemLabelsRepository.saveAll(List.of(nonTechnicalLabel)))
                .thenReturn(List.of(nonTechnicalLabel));

        // When
        contextSystemService.replaceLabels(system, labels);

        // Then
        verify(contextSystemLabelsRepository).saveAll(argThat(savedLabels -> {
            Iterator<ContextSystemLabel> iterator = savedLabels.iterator();
            ContextSystemLabel label = iterator.next();
            return !iterator.hasNext() && label.getName().equals(LABEL_NAME_1);
        }));

        assertThat(system.getLabels())
                .extracting(AbstractLabel::getName)
                .containsExactly(LABEL_NAME_1);
        assertThat(system.getLabels())
                .noneMatch(AbstractLabel::isTechnical);
    }

    @Test
    @DisplayName("Should handle empty labels list by removing all non-technical labels")
    void replaceLabelsWithEmptyListShouldRemoveAllNonTechnicalLabels() {
        // Given
        ContextSystemLabel userLabel1 = ContextSystemLabel.builder()
                .name(LABEL_NAME_1)
                .technical(false)
                .build();
        ContextSystemLabel userLabel2 = ContextSystemLabel.builder()
                .name(LABEL_NAME_2)
                .technical(false)
                .build();
        ContextSystemLabel technicalLabel = ContextSystemLabel.builder()
                .name("system-label")
                .technical(true)
                .build();

        ContextSystem system = ContextSystem.builder()
                .id(SYSTEM_ID)
                .name(SYSTEM_NAME)
                .labels(new java.util.LinkedHashSet<>(List.of(userLabel1, userLabel2, technicalLabel)))
                .build();

        List<ContextSystemLabel> emptyLabels = new ArrayList<>();

        // When
        contextSystemService.replaceLabels(system, emptyLabels);

        // Then
        verify(contextSystemLabelsRepository, never()).saveAll(any());

        assertThat(system.getLabels())
                .extracting(AbstractLabel::getName)
                .containsExactly("system-label");
        assertThat(system.getLabels())
                .allMatch(AbstractLabel::isTechnical);
    }

    private ArgumentCaptor<ActionLog> argCaptorLogAction(LogOperation expectedOperation, String expectedEntityId) {
        ArgumentCaptor<ActionLog> captor = ArgumentCaptor.forClass(ActionLog.class);
        verify(actionLogger).logAction(captor.capture());
        ActionLog captured = captor.getValue();
        assertThat(captured.getOperation()).isEqualTo(expectedOperation);
        assertThat(captured.getEntityId()).isEqualTo(expectedEntityId);
        return captor;
    }
}

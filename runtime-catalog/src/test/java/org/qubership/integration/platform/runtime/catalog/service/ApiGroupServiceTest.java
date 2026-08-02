package org.qubership.integration.platform.runtime.catalog.service;

import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.SpecificationDeleteException;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.SpecificationImportException;
import org.qubership.integration.platform.runtime.catalog.model.system.IntegrationSystemType;
import org.qubership.integration.platform.runtime.catalog.model.system.OperationProtocol;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog.ActionLog;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.ApiGroup;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.ApiGroupLabel;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.IntegrationSystem;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SystemModel;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.system.ApiGroupLabelsRepository;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.system.ApiGroupRepository;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.ProtocolExtractionService;
import org.qubership.integration.platform.runtime.catalog.service.helpers.ElementHelperService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the API group service: creation across its four overloads, protocol resolution, deletion
 * guards, label replacement, and the name/id uniqueness rules.
 */
class ApiGroupServiceTest {

    private static final MultipartFile[] NO_FILES = new MultipartFile[0];

    private final ApiGroupRepository apiGroupRepository = mock(ApiGroupRepository.class);
    private final ActionsLogService actionLogger = mock(ActionsLogService.class);
    private final SystemService systemService = mock(SystemService.class);
    private final ProtocolExtractionService protocolExtractionService = mock(ProtocolExtractionService.class);
    private final ApiGroupLabelsRepository apiGroupLabelsRepository = mock(ApiGroupLabelsRepository.class);
    private final ElementHelperService elementHelperService = mock(ElementHelperService.class);

    private final ApiGroupService service = new ApiGroupService(
            apiGroupRepository,
            actionLogger,
            systemService,
            protocolExtractionService,
            apiGroupLabelsRepository,
            elementHelperService);

    private static IntegrationSystem system(String id, String name) {
        IntegrationSystem system = new IntegrationSystem();
        system.setId(id);
        system.setName(name);
        // logSpecGroupAction switches over this enum, so a null type would fail every logging path.
        system.setIntegrationSystemType(IntegrationSystemType.EXTERNAL);
        return system;
    }

    private static ApiGroup group(String id, String name) {
        ApiGroup group = new ApiGroup();
        group.setId(id);
        group.setName(name);
        return group;
    }

    private static ApiGroupLabel label(String name, boolean technical) {
        ApiGroupLabel label = new ApiGroupLabel();
        label.setName(name);
        label.setTechnical(technical);
        return label;
    }

    private void saveReturnsArgument() {
        when(apiGroupRepository.save(any(ApiGroup.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    // --- checkSpecificationGroupUniqueness -------------------------------------------------

    @Test
    void shouldSkipUniquenessCheckWhenSystemHasNoGroups() {
        service.checkSpecificationGroupUniqueness(system("s1", "Service"));

        verify(apiGroupRepository, never()).findByIdInAndSystemIdNot(any(), anyString());
    }

    @Test
    void shouldRejectGroupAlreadyOwnedByAnotherSystem() {
        IntegrationSystem system = system("s1", "Service");
        system.addApiGroup(group("s1-Petstore", "Petstore"));
        ApiGroup duplicate = group("s1-Petstore", "Petstore");
        duplicate.setSystem(system("s2", "Other"));
        when(apiGroupRepository.findByIdInAndSystemIdNot(any(), eq("s1"))).thenReturn(duplicate);

        DuplicateKeyException exception = assertThrows(DuplicateKeyException.class,
                () -> service.checkSpecificationGroupUniqueness(system));

        assertTrue(exception.getMessage().contains("s2"));
    }

    @Test
    void shouldPassUniquenessCheckWhenNoDuplicateExists() {
        IntegrationSystem system = system("s1", "Service");
        system.addApiGroup(group("s1-Petstore", "Petstore"));
        when(apiGroupRepository.findByIdInAndSystemIdNot(any(), eq("s1"))).thenReturn(null);

        service.checkSpecificationGroupUniqueness(system);

        verify(apiGroupRepository).findByIdInAndSystemIdNot(any(), eq("s1"));
    }

    // --- createAndSaveSpecificationGroupWithProtocol ----------------------------------------

    @Test
    void shouldRejectCreationWhenSystemIsMissing() {
        SpecificationImportException exception = assertThrows(SpecificationImportException.class,
                () -> service.createAndSaveSpecificationGroupWithProtocol(null, "Petstore", "http", NO_FILES, null));

        assertEquals(AbstractApiGroupService.SYSTEM_NOT_FOUND_ERROR_MESSAGE, exception.getMessage());
    }

    @Test
    void shouldRejectCreationWhenGroupNameIsTaken() {
        IntegrationSystem system = system("s1", "Service");
        when(apiGroupRepository.findByNameAndSystem("Petstore", system)).thenReturn(group("s1-Petstore", "Petstore"));

        SpecificationImportException exception = assertThrows(SpecificationImportException.class,
                () -> service.createAndSaveSpecificationGroupWithProtocol(system, "Petstore", "http", NO_FILES, null));

        assertEquals(AbstractApiGroupService.SPECIFICATION_GROUP_NAME_ERROR_MESSAGE, exception.getMessage());
    }

    @Test
    void shouldCreateGroupAndTakeProtocolFromArgumentWhenSystemHasNone() {
        IntegrationSystem system = system("s1", "Service");
        saveReturnsArgument();

        ApiGroup created = service.createAndSaveSpecificationGroupWithProtocol(
                system, "Petstore", "http", NO_FILES, "http://example.test/spec");

        assertEquals("s1-Petstore", created.getId());
        assertEquals("Petstore", created.getName());
        assertEquals("http://example.test/spec", created.getUrl());
        assertEquals(OperationProtocol.HTTP, system.getProtocol());
        assertTrue(system.getApiGroups().contains(created));
        verify(systemService).validateSpecificationProtocol(system, OperationProtocol.HTTP);
        verify(systemService).update(system, false);
        verify(actionLogger).logAction(any(ActionLog.class));
    }

    @Test
    void shouldRejectCreationWhenProtocolIsNotSupported() {
        IntegrationSystem system = system("s1", "Service");

        SpecificationImportException exception = assertThrows(SpecificationImportException.class,
                () -> service.createAndSaveSpecificationGroupWithProtocol(
                        system, "Petstore", "not-a-protocol", NO_FILES, null));

        assertTrue(exception.getMessage().contains("Unsupported protocol"));
        verify(apiGroupRepository, never()).save(any(ApiGroup.class));
    }

    @Test
    void shouldRejectCreationWhenDetectedProtocolDiffersFromSystemProtocol() {
        IntegrationSystem system = system("s1", "Service");
        system.setProtocol(OperationProtocol.HTTP);
        when(protocolExtractionService.getOperationProtocol(any())).thenReturn(OperationProtocol.KAFKA);

        assertThrows(SpecificationImportException.class,
                () -> service.createAndSaveSpecificationGroupWithProtocol(system, "Petstore", null, NO_FILES, null));

        verify(apiGroupRepository, never()).save(any(ApiGroup.class));
    }

    @Test
    void shouldDetectProtocolFromFilesWhenArgumentIsBlank() {
        IntegrationSystem system = system("s1", "Service");
        when(protocolExtractionService.getOperationProtocol(any())).thenReturn(OperationProtocol.KAFKA);
        saveReturnsArgument();

        service.createAndSaveSpecificationGroupWithProtocol(system, "Events", "  ", NO_FILES, null);

        assertEquals(OperationProtocol.KAFKA, system.getProtocol());
    }

    // --- deleteByIdExists --------------------------------------------------------------------

    @Test
    void shouldReturnEmptyWhenDeletingUnknownGroup() {
        when(apiGroupRepository.findById("missing")).thenReturn(Optional.empty());

        assertTrue(service.deleteByIdExists("missing").isEmpty());
        verify(apiGroupRepository, never()).delete(any(ApiGroup.class));
    }

    @Test
    void shouldRejectDeleteByIdWhenGroupIsUsedByChain() {
        ApiGroup group = group("s1-Petstore", "Petstore");
        when(apiGroupRepository.findById("s1-Petstore")).thenReturn(Optional.of(group));
        when(elementHelperService.isSystemModelUsedByElement("s1-Petstore")).thenReturn(true);

        assertThrows(IllegalArgumentException.class, () -> service.deleteByIdExists("s1-Petstore"));
        verify(apiGroupRepository, never()).delete(any(ApiGroup.class));
    }

    @Test
    void shouldDeleteExistingGroupAndLogAction() {
        ApiGroup group = group("s1-Petstore", "Petstore");
        group.setSystem(system("s1", "Service"));
        when(apiGroupRepository.findById("s1-Petstore")).thenReturn(Optional.of(group));
        when(elementHelperService.isSystemModelUsedByElement("s1-Petstore")).thenReturn(false);

        Optional<ApiGroup> deleted = service.deleteByIdExists("s1-Petstore");

        assertTrue(deleted.isPresent());
        assertSame(group, deleted.get());
        verify(apiGroupRepository).delete(group);
        verify(actionLogger).logAction(any(ActionLog.class));
    }

    // --- createAndSaveSpecificationGroup overloads -------------------------------------------

    @Test
    void shouldRejectCreationWithExplicitIdWhenSystemIsMissing() {
        assertThrows(SpecificationImportException.class, () -> service.createAndSaveSpecificationGroup(
                (IntegrationSystem) null, "id", "Petstore", "openapi", null, false));
    }

    @Test
    void shouldCreateGroupWithExplicitIdAndProtocolFromSpecificationType() {
        IntegrationSystem system = system("s1", "Service");
        when(protocolExtractionService.getProtocol("openapi")).thenReturn(OperationProtocol.HTTP);
        saveReturnsArgument();

        ApiGroup created = service.createAndSaveSpecificationGroup(
                system, "explicit-id", "Petstore", "openapi", "http://example.test", true);

        assertEquals("explicit-id", created.getId());
        assertTrue(created.isSynchronization());
        assertEquals(OperationProtocol.HTTP, system.getProtocol());
        verify(systemService).update(system);
    }

    @Test
    void shouldBuildGroupIdFromSystemAndNameForDescriptionOverload() {
        IntegrationSystem system = system("s1", "Service");
        saveReturnsArgument();

        ApiGroup created = service.createAndSaveSpecificationGroup(
                system, "Petstore", "Pets and more", "http://example.test", false);

        assertEquals("s1-Petstore", created.getId());
        assertEquals("Pets and more", created.getDescription());
        assertFalse(created.isSynchronization());
    }

    @Test
    void shouldResolveSystemByIdForIdBasedOverload() {
        IntegrationSystem system = system("s1", "Service");
        when(systemService.getByIdOrNull("s1")).thenReturn(system);
        saveReturnsArgument();

        ApiGroup created = service.createAndSaveSpecificationGroup("s1", "Petstore", "Pets", "url", false);

        assertEquals("s1-Petstore", created.getId());
    }

    @Test
    void shouldGiveUniqueNameAndIdWhenGroupNameCollides() {
        IntegrationSystem system = system("s1", "Service");
        system.addApiGroup(group("s1-Petstore", "Petstore"));
        when(protocolExtractionService.getProtocol(anyString())).thenReturn(OperationProtocol.HTTP);
        saveReturnsArgument();

        ApiGroup created = service.createAndSaveUniqueSpecificationGroup(
                system, "Petstore", "openapi", "http://example.test", false);

        assertEquals("Petstore (1)", created.getName());
        assertEquals("s1-Petstore (1)", created.getId());
    }

    // --- lookups ------------------------------------------------------------------------------

    @Test
    void shouldReturnGroupBySystemIdAndUrl() {
        ApiGroup group = group("s1-Petstore", "Petstore");
        when(apiGroupRepository.findBySystemIdAndUrl("s1", "url")).thenReturn(group);

        assertSame(group, service.getSpecificationGroupBySystemIdAndUrl("s1", "url"));
    }

    @Test
    void shouldReportDuplicateWhenGroupUrlIsNotUnique() {
        when(apiGroupRepository.findBySystemIdAndUrl("s1", "url"))
                .thenThrow(new IncorrectResultSizeDataAccessException(1, 2));

        assertThrows(DuplicateKeyException.class, () -> service.getSpecificationGroupBySystemIdAndUrl("s1", "url"));
    }

    @Test
    void shouldReturnGroupByNameAndSystem() {
        IntegrationSystem system = system("s1", "Service");
        ApiGroup group = group("s1-Petstore", "Petstore");
        when(apiGroupRepository.findByNameAndSystem("Petstore", system)).thenReturn(group);

        assertSame(group, service.getSpecificationGroupByNameAndSystem("Petstore", system));
    }

    @Test
    void shouldReportDuplicateWhenGroupNameIsNotUnique() {
        IntegrationSystem system = system("s1", "Service");
        when(apiGroupRepository.findByNameAndSystem("Petstore", system))
                .thenThrow(new IncorrectResultSizeDataAccessException(1, 2));

        assertThrows(DuplicateKeyException.class,
                () -> service.getSpecificationGroupByNameAndSystem("Petstore", system));
    }

    @Test
    void shouldSortGroupsByNameDescendingAndModelsByVersion() {
        IntegrationSystem system = system("s1", "Service");
        ApiGroup alpha = group("s1-Alpha", "Alpha");
        alpha.setSystem(system);
        ApiGroup beta = group("s1-Beta", "Beta");
        beta.setSystem(system);
        SystemModel second = new SystemModel();
        second.setId("m2");
        second.setVersion("2.0.0");
        SystemModel first = new SystemModel();
        first.setId("m1");
        first.setVersion("1.0.0");
        beta.getSystemModels().add(second);
        beta.getSystemModels().add(first);
        when(apiGroupRepository.findAllBySystemId("s1")).thenReturn(new ArrayList<>(List.of(alpha, beta)));
        when(elementHelperService.findBySystemAndGroupId(anyString(), anyString())).thenReturn(List.of());

        List<ApiGroup> groups = service.getSpecificationGroups("s1");

        assertEquals(List.of("Beta", "Alpha"), groups.stream().map(ApiGroup::getName).toList());
        assertEquals(List.of("1.0.0", "2.0.0"),
                groups.get(0).getSystemModels().stream().map(SystemModel::getVersion).toList());
    }

    // --- delete --------------------------------------------------------------------------------

    @Test
    void shouldRejectDeleteWhenGroupIsUsedByChain() {
        when(elementHelperService.isSystemModelUsedByElement("s1-Petstore")).thenReturn(true);

        assertThrows(SpecificationDeleteException.class, () -> service.delete("s1-Petstore"));
        verify(apiGroupRepository, never()).delete(any(ApiGroup.class));
    }

    @Test
    void shouldDeleteGroupAndDetachItFromSystem() {
        IntegrationSystem system = system("s1", "Service");
        ApiGroup group = group("s1-Petstore", "Petstore");
        system.addApiGroup(group);
        group.setSystem(system);
        when(elementHelperService.isSystemModelUsedByElement("s1-Petstore")).thenReturn(false);
        when(apiGroupRepository.getReferenceById("s1-Petstore")).thenReturn(group);

        service.delete("s1-Petstore");

        verify(apiGroupRepository).delete(group);
        assertFalse(system.getApiGroups().contains(group));
        verify(actionLogger).logAction(any(ActionLog.class));
    }

    // --- update and labels ----------------------------------------------------------------------

    @Test
    void shouldSaveAndLogUpdateWhenNoLabelsGiven() {
        ApiGroup group = group("s1-Petstore", "Petstore");
        group.setSystem(system("s1", "Service"));
        saveReturnsArgument();

        ApiGroup updated = service.update(group);

        assertSame(group, updated);
        verify(apiGroupRepository).save(group);
        verify(apiGroupLabelsRepository, never()).saveAll(any());
        verify(actionLogger).logAction(any(ActionLog.class));
    }

    @Test
    void shouldReplaceLabelsKeepingTechnicalOnesAndDroppingAbsentOnes() {
        ApiGroup group = group("s1-Petstore", "Petstore");
        group.setLabels(new LinkedHashSet<>(List.of(label("keep", false), label("drop", false), label("tech", true))));
        List<ApiGroupLabel> newLabels = new ArrayList<>(List.of(label("keep", false), label("fresh", false)));
        when(apiGroupLabelsRepository.saveAll(any()))
                .thenAnswer(invocation -> new ArrayList<ApiGroupLabel>(invocation.getArgument(0)));

        service.replaceLabels(group, newLabels);

        Set<String> names = group.getLabels().stream().map(ApiGroupLabel::getName).collect(Collectors.toSet());
        assertTrue(names.contains("keep"), "an unchanged label stays");
        assertTrue(names.contains("tech"), "a technical label is never dropped");
        assertTrue(names.contains("fresh"), "a new label is added");
        assertFalse(names.contains("drop"), "a label absent from the new set is removed");
    }

    @Test
    void shouldIgnoreLabelReplacementWhenNewLabelsAreNull() {
        ApiGroup group = group("s1-Petstore", "Petstore");
        group.setLabels(new LinkedHashSet<>(List.of(label("keep", false))));

        service.replaceLabels(group, null);

        assertEquals(1, group.getLabels().size());
        verify(apiGroupLabelsRepository, never()).saveAll(any());
    }

    // --- getUniqueName ---------------------------------------------------------------------------

    @Test
    void shouldKeepDesiredNameWhenItIsFree() {
        IntegrationSystem system = system("s1", "Service");

        assertEquals("Petstore", service.getUniqueName(system, "Petstore"));
    }

    @Test
    void shouldAppendCounterUntilNameIsFree() {
        IntegrationSystem system = system("s1", "Service");
        system.addApiGroup(group("s1-Petstore", "Petstore"));
        system.addApiGroup(group("s1-Petstore (1)", "Petstore (1)"));

        assertEquals("Petstore (2)", service.getUniqueName(system, "Petstore"));
    }

    // --- getById ---------------------------------------------------------------------------------

    @Test
    void shouldReturnNullWhenGroupIsNotFoundById() {
        when(apiGroupRepository.findById("missing")).thenReturn(Optional.empty());

        assertNull(service.getById("missing"));
    }

    @Test
    void shouldReturnGroupById() {
        ApiGroup group = group("s1-Petstore", "Petstore");
        when(apiGroupRepository.findById("s1-Petstore")).thenReturn(Optional.of(group));

        assertSame(group, service.getById("s1-Petstore"));
    }
}

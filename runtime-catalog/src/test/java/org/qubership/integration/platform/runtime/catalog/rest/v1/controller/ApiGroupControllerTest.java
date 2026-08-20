package org.qubership.integration.platform.runtime.catalog.rest.v1.controller;

import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.ApiGroup;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.ApiGroupCreationRequestDTO;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.ApiGroupDTO;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.ApiGroupRequestDTO;
import org.qubership.integration.platform.runtime.catalog.rest.v1.mapper.ApiGroupMapper;
import org.qubership.integration.platform.runtime.catalog.service.ApiGroupService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Covers the API group REST surface: listing, deletion, the sync-toggle patch, and creation. */
class ApiGroupControllerTest {

    private final ApiGroupService apiGroupService = mock(ApiGroupService.class);
    private final ApiGroupMapper apiGroupMapper = mock(ApiGroupMapper.class);
    private final ApiGroupController controller = new ApiGroupController(apiGroupService, apiGroupMapper);

    @BeforeEach
    void bindRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/specificationGroups");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void unbindRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    private static ApiGroupDTO dto(String id) {
        ApiGroupDTO dto = new ApiGroupDTO();
        dto.setId(id);
        return dto;
    }

    @Test
    void shouldReturnMappedGroupsForService() {
        ApiGroup group = new ApiGroup();
        group.setId("g1");
        List<ApiGroupDTO> mapped = List.of(dto("g1"));
        when(apiGroupService.getSpecificationGroups("s1")).thenReturn(List.of(group));
        when(apiGroupMapper.toApiGroupDTOs(List.of(group))).thenReturn(mapped);

        ResponseEntity<List<ApiGroupDTO>> response = controller.getApiGroups("s1");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertSame(mapped, response.getBody());
    }

    @Test
    void shouldDelegateDeletionToTheService() {
        controller.deleteApiGroup("g1");

        verify(apiGroupService).delete("g1");
    }

    @Test
    void shouldReportUnknownGroupAsNotFound() {
        // GlobalExceptionHandler turns EntityNotFoundException into 404, the same answer PUT/PATCH
        // /v1/systems/{id} and POST /v1/systems/{id}/environments give for an unknown id.
        when(apiGroupService.getById("missing")).thenReturn(null);

        EntityNotFoundException thrown = assertThrows(EntityNotFoundException.class,
                () -> controller.updateSyncStatus("missing", new ApiGroupRequestDTO()));

        assertTrue(thrown.getMessage().contains("missing"));
        assertTrue(thrown.getMessage().contains("Nothing was updated"));
        verify(apiGroupService, never()).update(any(), any());
    }

    @Test
    void shouldUpdateSyncStatusOfExistingGroup() {
        ApiGroup group = new ApiGroup();
        group.setId("g1");
        ApiGroupRequestDTO request = new ApiGroupRequestDTO();
        request.setSynchronization(true);
        ApiGroupDTO mapped = dto("g1");
        when(apiGroupService.getById("g1")).thenReturn(group);
        when(apiGroupService.update(any(), any())).thenReturn(group);
        when(apiGroupMapper.toApiGroupDTO(group)).thenReturn(mapped);

        ResponseEntity<ApiGroupDTO> response = controller.updateSyncStatus("g1", request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertSame(mapped, response.getBody());
        verify(apiGroupMapper).mergeWithoutLabels(request, group);
    }

    @Test
    void shouldReturnCreatedGroupWithLocationHeader() {
        ApiGroupCreationRequestDTO params = new ApiGroupCreationRequestDTO();
        params.setSystemId("s1");
        params.setName("Petstore");
        params.setDescription("Pets");
        params.setUrl("http://example.test");
        params.setSynchronization(true);
        ApiGroup created = new ApiGroup();
        created.setId("s1-Petstore");
        when(apiGroupService.createAndSaveSpecificationGroup("s1", "Petstore", "Pets", "http://example.test", true))
                .thenReturn(created);
        when(apiGroupMapper.toApiGroupDTO(created)).thenReturn(dto("s1-Petstore"));

        ResponseEntity<ApiGroupDTO> response = controller.createApiGroup(params);

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertNotNull(response.getHeaders().getLocation());
        assertEquals("/v1/specificationGroups/s1-Petstore", response.getHeaders().getLocation().getPath());
    }
}

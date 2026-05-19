/*
 * Copyright 2024-2025 NetCracker Technology Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.qubership.integration.platform.runtime.catalog.rest.v1.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.system.ImportSystemResult;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.mcp.MCPSystem;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.system.imports.ImportSystemStatus;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.system.mcp.MCPSystemFilterRequestDTO;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.system.mcp.MCPSystemRequestDTO;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.system.mcp.MCPSystemResponseDTO;
import org.qubership.integration.platform.runtime.catalog.rest.v1.mapper.MCPSystemMapper;
import org.qubership.integration.platform.runtime.catalog.service.MCPSystemService;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.MCPSystemImportExportService;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class MCPSystemControllerTest {

    private static final String BASE_URL = "/v1/catalog/mcp-system";
    private static final String SYSTEM_ID = "system-id-1";

    @Mock
    MCPSystemService mcpSystemService;

    @Mock
    MCPSystemMapper mcpSystemMapper;

    @Mock
    MCPSystemImportExportService mcpSystemImportExportService;

    @InjectMocks
    MCPSystemController mcpSystemController;

    MockMvc mockMvc;
    ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(mcpSystemController).build();
        objectMapper = new ObjectMapper();
    }

    // GET /v1/catalog/mcp-system

    @Test
    @DisplayName("GET /v1/catalog/mcp-system returns 200 with list of systems")
    void getAllReturns200WithDtos() throws Exception {
        MCPSystem system = MCPSystem.builder().id(SYSTEM_ID).name("s1").build();
        MCPSystemResponseDTO dto = new MCPSystemResponseDTO();
        dto.setId(SYSTEM_ID);
        dto.setName("s1");

        when(mcpSystemService.findAll(false)).thenReturn(List.of(system));
        when(mcpSystemMapper.toResponseDtos(List.of(system))).thenReturn(List.of(dto));

        mockMvc.perform(get(BASE_URL))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id", is(SYSTEM_ID)));
    }

    @Test
    @DisplayName("GET /v1/catalog/mcp-system?withChains=true passes withChains=true to service")
    void getAllWithChainsFlagPassesTrueToService() throws Exception {
        when(mcpSystemService.findAll(true)).thenReturn(List.of());
        when(mcpSystemMapper.toResponseDtos(any())).thenReturn(List.of());

        mockMvc.perform(get(BASE_URL).param("withChains", "true"))
                .andExpect(status().isOk());

        verify(mcpSystemService).findAll(true);
    }

    // GET /v1/catalog/mcp-system/{id}

    @Test
    @DisplayName("GET /v1/catalog/mcp-system/{id} returns 200 when system found")
    void getByIdReturns200WhenFound() throws Exception {
        MCPSystem system = MCPSystem.builder().id(SYSTEM_ID).build();
        MCPSystemResponseDTO dto = new MCPSystemResponseDTO();
        dto.setId(SYSTEM_ID);

        when(mcpSystemService.findById(SYSTEM_ID)).thenReturn(Optional.of(system));
        when(mcpSystemMapper.toResponseDto(system)).thenReturn(dto);

        mockMvc.perform(get(BASE_URL + "/{id}", SYSTEM_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(SYSTEM_ID)));
    }

    @Test
    @DisplayName("GET /v1/catalog/mcp-system/{id} returns 404 when system not found")
    void getByIdReturns404WhenNotFound() throws Exception {
        when(mcpSystemService.findById(SYSTEM_ID)).thenReturn(Optional.empty());

        mockMvc.perform(get(BASE_URL + "/{id}", SYSTEM_ID))
                .andExpect(status().isNotFound());
    }

    // POST /v1/catalog/mcp-system

    @Test
    @DisplayName("POST /v1/catalog/mcp-system returns 201 with created system DTO")
    void createReturns201WithDto() throws Exception {
        MCPSystemRequestDTO request = new MCPSystemRequestDTO();
        request.setName("new-system");

        MCPSystem created = MCPSystem.builder().id(SYSTEM_ID).name("new-system").build();
        MCPSystemResponseDTO dto = new MCPSystemResponseDTO();
        dto.setId(SYSTEM_ID);
        dto.setName("new-system");

        when(mcpSystemService.create(any(MCPSystemRequestDTO.class))).thenReturn(created);
        when(mcpSystemMapper.toResponseDto(created)).thenReturn(dto);

        mockMvc.perform(post(BASE_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", is(SYSTEM_ID)))
                .andExpect(jsonPath("$.name", is("new-system")));
    }

    // PUT /v1/catalog/mcp-system/{id}

    @Test
    @DisplayName("PUT /v1/catalog/mcp-system/{id} returns 200 with updated system DTO")
    void updateReturns200WithDto() throws Exception {
        MCPSystemRequestDTO request = new MCPSystemRequestDTO();
        request.setName("updated-name");

        MCPSystem updated = MCPSystem.builder().id(SYSTEM_ID).name("updated-name").build();
        MCPSystemResponseDTO dto = new MCPSystemResponseDTO();
        dto.setId(SYSTEM_ID);
        dto.setName("updated-name");

        when(mcpSystemService.update(eq(SYSTEM_ID), any(MCPSystemRequestDTO.class))).thenReturn(updated);
        when(mcpSystemMapper.toResponseDto(updated)).thenReturn(dto);

        mockMvc.perform(put(BASE_URL + "/{id}", SYSTEM_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(SYSTEM_ID)))
                .andExpect(jsonPath("$.name", is("updated-name")));
    }

    // DELETE /v1/catalog/mcp-system/{id}

    @Test
    @DisplayName("DELETE /v1/catalog/mcp-system/{id} returns 204")
    void deleteReturns204() throws Exception {
        doNothing().when(mcpSystemService).deleteById(SYSTEM_ID);

        mockMvc.perform(delete(BASE_URL + "/{id}", SYSTEM_ID))
                .andExpect(status().isNoContent());

        verify(mcpSystemService).deleteById(SYSTEM_ID);
    }

    // POST /v1/catalog/mcp-system/filter

    @Test
    @DisplayName("POST /v1/catalog/mcp-system/filter returns 200 with filtered results")
    void filterReturns200WithResults() throws Exception {
        MCPSystemFilterRequestDTO filterRequest = new MCPSystemFilterRequestDTO();
        filterRequest.setSearchString("test");
        filterRequest.setFilters(List.of());

        MCPSystem system = MCPSystem.builder().id(SYSTEM_ID).build();
        MCPSystemResponseDTO dto = new MCPSystemResponseDTO();
        dto.setId(SYSTEM_ID);

        when(mcpSystemService.filter(eq("test"), any())).thenReturn(List.of(system));
        when(mcpSystemMapper.toResponseDtos(List.of(system))).thenReturn(List.of(dto));

        mockMvc.perform(post(BASE_URL + "/filter")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(filterRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id", is(SYSTEM_ID)));
    }

    // POST /v1/catalog/mcp-system/export

    @Test
    @DisplayName("POST /v1/catalog/mcp-system/export returns 200 with attachment when data present")
    void exportReturns200WithAttachmentWhenDataPresent() throws Exception {
        byte[] data = new byte[]{1, 2, 3};
        when(mcpSystemImportExportService.export(any())).thenReturn(data);

        mockMvc.perform(post(BASE_URL + "/export")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(List.of(SYSTEM_ID))))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("attachment")));
    }

    @Test
    @DisplayName("POST /v1/catalog/mcp-system/export returns 204 when no data")
    void exportReturns204WhenNoData() throws Exception {
        when(mcpSystemImportExportService.export(any())).thenReturn(null);

        mockMvc.perform(post(BASE_URL + "/export")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(List.of())))
                .andExpect(status().isNoContent());
    }

    // POST /v1/catalog/mcp-system/import

    @Test
    @DisplayName("POST /v1/catalog/mcp-system/import returns 200 when all results are OK")
    void importSystemsReturns200WhenAllOk() throws Exception {
        ImportSystemResult result = ImportSystemResult.builder()
                .id(SYSTEM_ID)
                .status(ImportSystemStatus.CREATED)
                .build();

        when(mcpSystemImportExportService.importSystems(any(), any())).thenReturn(List.of(result));

        MockMultipartFile file = new MockMultipartFile("file", "systems.zip",
                MediaType.APPLICATION_OCTET_STREAM_VALUE, new byte[]{1, 2, 3});

        mockMvc.perform(multipart(BASE_URL + "/import").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id", is(SYSTEM_ID)));
    }

    @Test
    @DisplayName("POST /v1/catalog/mcp-system/import returns 207 when some results have errors")
    void importSystemsReturns207WhenSomeErrors() throws Exception {
        ImportSystemResult ok = ImportSystemResult.builder()
                .id("id-1").status(ImportSystemStatus.CREATED).build();
        ImportSystemResult error = ImportSystemResult.builder()
                .id("id-2").status(ImportSystemStatus.ERROR).build();

        when(mcpSystemImportExportService.importSystems(any(), any())).thenReturn(List.of(ok, error));

        MockMultipartFile file = new MockMultipartFile("file", "systems.zip",
                MediaType.APPLICATION_OCTET_STREAM_VALUE, new byte[]{1, 2, 3});

        mockMvc.perform(multipart(BASE_URL + "/import").file(file))
                .andExpect(status().isMultiStatus());
    }

    @Test
    @DisplayName("POST /v1/catalog/mcp-system/import returns 204 when result list is empty")
    void importSystemsReturns204WhenEmpty() throws Exception {
        when(mcpSystemImportExportService.importSystems(any(), any())).thenReturn(List.of());

        MockMultipartFile file = new MockMultipartFile("file", "systems.zip",
                MediaType.APPLICATION_OCTET_STREAM_VALUE, new byte[]{1, 2, 3});

        mockMvc.perform(multipart(BASE_URL + "/import").file(file))
                .andExpect(status().isNoContent());
    }

    // POST /v1/catalog/mcp-system/import/preview

    @Test
    @DisplayName("POST /v1/catalog/mcp-system/import/preview returns 200 with preview results")
    void getImportPreviewReturns200WhenResultsPresent() throws Exception {
        ImportSystemResult result = ImportSystemResult.builder()
                .id(SYSTEM_ID).status(ImportSystemStatus.CREATED).build();

        when(mcpSystemImportExportService.getImportPreview(any())).thenReturn(List.of(result));

        MockMultipartFile file = new MockMultipartFile("file", "systems.zip",
                MediaType.APPLICATION_OCTET_STREAM_VALUE, new byte[]{1, 2, 3});

        mockMvc.perform(multipart(BASE_URL + "/import/preview").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id", is(SYSTEM_ID)));
    }

    @Test
    @DisplayName("POST /v1/catalog/mcp-system/import/preview returns 204 when no preview results")
    void getImportPreviewReturns204WhenEmpty() throws Exception {
        when(mcpSystemImportExportService.getImportPreview(any())).thenReturn(List.of());

        MockMultipartFile file = new MockMultipartFile("file", "systems.zip",
                MediaType.APPLICATION_OCTET_STREAM_VALUE, new byte[]{1, 2, 3});

        mockMvc.perform(multipart(BASE_URL + "/import/preview").file(file))
                .andExpect(status().isNoContent());
    }
}

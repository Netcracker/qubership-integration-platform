package org.qubership.integration.platform.runtime.catalog.rest.v1.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.IntegrationSystem;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.system.SystemDTO;
import org.qubership.integration.platform.runtime.catalog.rest.v1.mapper.SystemMapper;
import org.qubership.integration.platform.runtime.catalog.service.ElementService;
import org.qubership.integration.platform.runtime.catalog.service.SystemService;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class SystemControllerChainUsageTest {

    private static final String BASE_URL = "/v1/systems";

    @Mock
    SystemService systemService;

    @Mock
    SystemMapper systemMapper;

    @Mock
    ElementService elementService;

    @InjectMocks
    SystemController systemController;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(systemController).build();
        lenient().when(systemMapper.toResponseDTOs(anyList())).thenReturn(List.of(new SystemDTO()));
    }

    private List<IntegrationSystem> oneSystem() {
        IntegrationSystem system = new IntegrationSystem();
        system.setId("system-1");
        return List.of(system);
    }

    @Test
    @DisplayName("GET /v1/systems leaves out chain usage unless it is asked for")
    void listSkipsChainUsageByDefault() throws Exception {
        when(systemService.getAll()).thenReturn(oneSystem());

        mockMvc.perform(get(BASE_URL).param("modelType", "all")).andExpect(status().isOk());

        verify(systemService, never()).enrichWithChainUsage(any());
    }

    @Test
    @DisplayName("GET /v1/systems?includeChainUsage=true resolves chain usage for the list")
    void listResolvesChainUsageWhenRequested() throws Exception {
        List<IntegrationSystem> systems = oneSystem();
        when(systemService.getAll()).thenReturn(systems);

        mockMvc.perform(get(BASE_URL).param("modelType", "all").param("includeChainUsage", "true"))
                .andExpect(status().isOk());

        verify(systemService).enrichWithChainUsage(systems);
    }

    @Test
    @DisplayName("GET /v1/systems with a model type filter resolves chain usage too")
    void listByModelTypeResolvesChainUsageWhenRequested() throws Exception {
        List<IntegrationSystem> systems = oneSystem();
        when(systemService.getNotDeprecatedAndByModelType(anyList())).thenReturn(systems);

        mockMvc.perform(get(BASE_URL).param("modelType", "http").param("includeChainUsage", "true"))
                .andExpect(status().isOk());

        verify(systemService).enrichWithChainUsage(systems);
        verify(systemService, never()).getAll();
    }

    @Test
    @DisplayName("POST /v1/systems/search?includeChainUsage=true resolves chain usage for the result")
    void searchResolvesChainUsageWhenRequested() throws Exception {
        List<IntegrationSystem> systems = oneSystem();
        when(systemService.searchSystems(any())).thenReturn(systems);

        mockMvc.perform(post(BASE_URL + "/search")
                        .contentType("application/json")
                        .content("{\"searchCondition\":\"pay\"}")
                        .param("includeChainUsage", "true"))
                .andExpect(status().isOk());

        verify(systemService).enrichWithChainUsage(systems);
    }

    @Test
    @DisplayName("POST /v1/systems/search leaves out chain usage unless it is asked for")
    void searchSkipsChainUsageByDefault() throws Exception {
        when(systemService.searchSystems(any())).thenReturn(oneSystem());

        mockMvc.perform(post(BASE_URL + "/search")
                        .contentType("application/json")
                        .content("{\"searchCondition\":\"pay\"}"))
                .andExpect(status().isOk());

        verify(systemService, never()).enrichWithChainUsage(any());
    }

    @Test
    @DisplayName("POST /v1/systems/filter?includeChainUsage=true resolves chain usage for the result")
    void filterResolvesChainUsageWhenRequested() throws Exception {
        List<IntegrationSystem> systems = oneSystem();
        when(systemService.findByFilterRequest(anyList())).thenReturn(systems);

        mockMvc.perform(post(BASE_URL + "/filter")
                        .contentType("application/json")
                        .content("[]")
                        .param("includeChainUsage", "true"))
                .andExpect(status().isOk());

        verify(systemService).enrichWithChainUsage(systems);
    }

    @Test
    @DisplayName("POST /v1/systems/filter leaves out chain usage unless it is asked for")
    void filterSkipsChainUsageByDefault() throws Exception {
        when(systemService.findByFilterRequest(anyList())).thenReturn(oneSystem());

        mockMvc.perform(post(BASE_URL + "/filter").contentType("application/json").content("[]"))
                .andExpect(status().isOk());

        verify(systemService, never()).enrichWithChainUsage(any());
    }
}

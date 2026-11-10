package org.qubership.integration.platform.runtime.catalog.rest.v1.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElementSearchCriteria;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.chain.UpdateRolesRequest;
import org.qubership.integration.platform.runtime.catalog.service.ChainRolesService;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ChainAccessControlControllerTest {

    private static final String ACCESS_CONTROL_URL = "/v1/catalog/chains/access-control";
    private static final String REDEPLOY_URL = ACCESS_CONTROL_URL + "/redeploy";
    private static final String ELEMENT_ID = "element-a";
    private static final String CHAIN_ID = "chain-a";

    @Mock
    private ChainRolesService chainRolesService;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ChainAccessControlController(chainRolesService))
                .build();
    }

    @Test
    void findBySearchRequestUsesAccessControlEndpoint() throws Exception {
        mockMvc.perform(post(ACCESS_CONTROL_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());

        verify(chainRolesService).findAllChainByHttpTrigger(any(ChainElementSearchCriteria.class), eq(false));
    }

    @Test
    void updateRolesUsesAccessControlEndpoint() throws Exception {
        UpdateRolesRequest request = new UpdateRolesRequest();
        request.setElementId(ELEMENT_ID);
        request.setRoles(Set.of("reader"));

        mockMvc.perform(put(ACCESS_CONTROL_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(List.of(request))))
                .andExpect(status().isNoContent());

        verify(chainRolesService).updateRoles(anyList());
    }

    @Test
    void bulkRedeployUsesAccessControlEndpoint() throws Exception {
        mockMvc.perform(put(REDEPLOY_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(List.of(CHAIN_ID))))
                .andExpect(status().isNoContent());

        verify(chainRolesService).redeploy(List.of(CHAIN_ID));
    }
}

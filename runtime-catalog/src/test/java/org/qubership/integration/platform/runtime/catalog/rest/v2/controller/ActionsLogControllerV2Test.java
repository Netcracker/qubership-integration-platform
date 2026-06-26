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

package org.qubership.integration.platform.runtime.catalog.rest.v2.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.runtime.catalog.model.dto.actionlog.ActionLogDTO;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog.EntityType;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog.LogOperation;
import org.qubership.integration.platform.runtime.catalog.rest.v2.dto.ActionLogSearchRequest;
import org.qubership.integration.platform.runtime.catalog.rest.v2.dto.ActionLogSearchResponse;
import org.qubership.integration.platform.runtime.catalog.service.ActionsLogService;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ActionsLogControllerV2Test {

    private static final String BASE_URL = "/v2/catalog/actions-log";

    @Mock
    ActionsLogService actionsLogService;

    @InjectMocks
    ActionsLogControllerV2 actionsLogControllerV2;

    MockMvc mockMvc;
    ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(actionsLogControllerV2).build();
        objectMapper = new ObjectMapper();
    }

    @Test
    @DisplayName("POST /v2/catalog/actions-log returns paged action logs")
    void findBySearchRequestReturnsPagedActionLogs() throws Exception {
        ActionLogSearchRequest request = new ActionLogSearchRequest();
        request.setOffset(0);
        request.setLimit(100);

        ActionLogDTO log = ActionLogDTO.builder()
                .id("log-1")
                .actionTime(1_700_000_000_000L)
                .entityType(EntityType.CHAIN)
                .operation(LogOperation.CREATE)
                .build();
        ActionLogSearchResponse response = new ActionLogSearchResponse(1, List.of(log));

        when(actionsLogService.findByPagedSearchRequest(any(ActionLogSearchRequest.class)))
                .thenReturn(response);

        mockMvc.perform(post(BASE_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.offset").value(1))
                .andExpect(jsonPath("$.actionLogs[0].id").value("log-1"))
                .andExpect(jsonPath("$.actionLogs[0].entityType").value("CHAIN"))
                .andExpect(jsonPath("$.actionLogs[0].operation").value("CREATE"));

        verify(actionsLogService).findByPagedSearchRequest(any(ActionLogSearchRequest.class));
    }
}

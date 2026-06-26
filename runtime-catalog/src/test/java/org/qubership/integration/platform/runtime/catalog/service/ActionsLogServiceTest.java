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

package org.qubership.integration.platform.runtime.catalog.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.InvalidEnumConstantException;
import org.qubership.integration.platform.runtime.catalog.model.dto.actionlog.ActionLogDTO;
import org.qubership.integration.platform.runtime.catalog.model.mapper.mapping.ActionsLogMapper;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.User;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog.ActionLog;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.actionlog.ActionLogRepository;
import org.qubership.integration.platform.runtime.catalog.rest.v2.dto.ActionLogSearchRequest;
import org.qubership.integration.platform.runtime.catalog.rest.v2.dto.ActionLogSearchResponse;
import org.springframework.data.domain.AuditorAware;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ActionsLogServiceTest {

    @Mock
    ActionLogRepository actionLogRepository;

    @Mock
    ActionsLogMapper actionsLogMapper;

    @Mock
    AuditorAware<User> auditor;

    ActionsLogService actionsLogService;

    @BeforeEach
    void setUp() {
        actionsLogService = new ActionsLogService(actionLogRepository, actionsLogMapper, auditor);
    }

    @Test
    @DisplayName("findByPagedSearchRequest returns mapped logs with next offset")
    void findByPagedSearchRequestReturnsMappedLogs() {
        ActionLogSearchRequest request = new ActionLogSearchRequest();
        request.setOffset(0);
        request.setLimit(50);

        ActionLog log = ActionLog.builder().id("log-1").build();
        ActionLogDTO dto = ActionLogDTO.builder().id("log-1").build();
        List<ActionLog> logs = List.of(log);

        when(actionLogRepository.findActionLogsByFilter(0, 50, Collections.emptyList()))
                .thenReturn(logs);
        when(actionsLogMapper.asDTO(logs)).thenReturn(List.of(dto));

        ActionLogSearchResponse response = actionsLogService.findByPagedSearchRequest(request);

        assertThat(response.getOffset()).isEqualTo(1);
        assertThat(response.getActionLogs()).containsExactly(dto);
    }

    @Test
    @DisplayName("findByPagedSearchRequest normalizes negative offset to zero")
    void findByPagedSearchRequestNormalizesNegativeOffset() {
        ActionLogSearchRequest request = new ActionLogSearchRequest();
        request.setOffset(-5);
        request.setLimit(50);

        when(actionLogRepository.findActionLogsByFilter(0, 50, Collections.emptyList()))
                .thenReturn(Collections.emptyList());
        when(actionsLogMapper.asDTO(Collections.emptyList())).thenReturn(Collections.emptyList());

        ActionLogSearchResponse response = actionsLogService.findByPagedSearchRequest(request);

        assertThat(response.getOffset()).isZero();
        assertThat(response.getActionLogs()).isEmpty();
    }

    @Test
    @DisplayName("findByPagedSearchRequest uses default limit when limit is not positive")
    void findByPagedSearchRequestUsesDefaultLimit() {
        ActionLogSearchRequest request = new ActionLogSearchRequest();
        request.setOffset(0);
        request.setLimit(0);

        when(actionLogRepository.findActionLogsByFilter(0, 100, Collections.emptyList()))
                .thenReturn(Collections.emptyList());
        when(actionsLogMapper.asDTO(Collections.emptyList())).thenReturn(Collections.emptyList());

        actionsLogService.findByPagedSearchRequest(request);
    }

    @Test
    @DisplayName("findByPagedSearchRequest caps limit at 1000")
    void findByPagedSearchRequestCapsLimit() {
        ActionLogSearchRequest request = new ActionLogSearchRequest();
        request.setOffset(0);
        request.setLimit(5000);

        when(actionLogRepository.findActionLogsByFilter(0, 1000, Collections.emptyList()))
                .thenReturn(Collections.emptyList());
        when(actionsLogMapper.asDTO(Collections.emptyList())).thenReturn(Collections.emptyList());

        actionsLogService.findByPagedSearchRequest(request);
    }

    @Test
    @DisplayName("findByPagedSearchRequest treats null filters as empty list")
    void findByPagedSearchRequestTreatsNullFiltersAsEmpty() {
        ActionLogSearchRequest request = new ActionLogSearchRequest();
        request.setOffset(0);
        request.setLimit(50);
        request.setFilters(null);

        when(actionLogRepository.findActionLogsByFilter(0, 50, Collections.emptyList()))
                .thenReturn(Collections.emptyList());
        when(actionsLogMapper.asDTO(Collections.emptyList())).thenReturn(Collections.emptyList());

        actionsLogService.findByPagedSearchRequest(request);
    }

    @Test
    @DisplayName("findByPagedSearchRequest returns empty result on invalid enum filter")
    void findByPagedSearchRequestReturnsEmptyOnInvalidEnum() {
        ActionLogSearchRequest request = new ActionLogSearchRequest();
        request.setOffset(10);
        request.setLimit(50);

        when(actionLogRepository.findActionLogsByFilter(10, 50, Collections.emptyList()))
                .thenThrow(new InvalidEnumConstantException("invalid enum"));

        ActionLogSearchResponse response = actionsLogService.findByPagedSearchRequest(request);

        assertThat(response.getOffset()).isEqualTo(10);
        assertThat(response.getActionLogs()).isEmpty();
    }
}

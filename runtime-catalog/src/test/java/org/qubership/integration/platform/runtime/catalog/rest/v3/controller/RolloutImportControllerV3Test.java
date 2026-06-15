package org.qubership.integration.platform.runtime.catalog.rest.v3.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.runtime.catalog.model.constant.RolloutImportConstants;
import org.qubership.integration.platform.runtime.catalog.rest.v3.dto.rolloutimport.RolloutImportConfigurationRequest;
import org.qubership.integration.platform.runtime.catalog.service.rolloutimport.RolloutImportService;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class RolloutImportControllerV3Test {

    private static final String BASE_URL = "/v3/rollout-import";
    private static final String SNAPSHOT_PATH = "/{snapshotId}";
    private static final String SNAPSHOT_ID = "snapshot-abc-123";

    @Mock
    RolloutImportService rolloutImportService;

    @InjectMocks
    RolloutImportControllerV3 controller;

    MockMvc mockMvc;
    ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        objectMapper = new ObjectMapper();
    }

    @Test
    @DisplayName("PUT /{snapshotId} returns HTTP 202 Accepted")
    void receiveSnapshotReturns202() throws Exception {
        RolloutImportConfigurationRequest request = new RolloutImportConfigurationRequest();

        mockMvc.perform(put(BASE_URL + SNAPSHOT_PATH, SNAPSHOT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted());
    }

    @Test
    @DisplayName("PUT /{snapshotId} response body contains status 'Rollout In Progress'")
    void receiveSnapshotResponseContainsInProgressStatus() throws Exception {
        RolloutImportConfigurationRequest request = new RolloutImportConfigurationRequest();

        mockMvc.perform(put(BASE_URL + SNAPSHOT_PATH, SNAPSHOT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(jsonPath("$.status", is(RolloutImportConstants.STATUS_ROLLOUT_IN_PROGRESS)));
    }

    @Test
    @DisplayName("PUT /{snapshotId} delegates to rolloutImportService.processAsync with correct snapshotId")
    void receiveSnapshotDelegatesWithCorrectSnapshotId() throws Exception {
        RolloutImportConfigurationRequest request = new RolloutImportConfigurationRequest();

        mockMvc.perform(put(BASE_URL + SNAPSHOT_PATH, SNAPSHOT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted());

        verify(rolloutImportService).processAsync(eq(SNAPSHOT_ID), any(RolloutImportConfigurationRequest.class), isNull());
    }

    @Test
    @DisplayName("PUT /{snapshotId} passes X-Callback-Url header to service")
    void receiveSnapshotPassesCallbackUrlHeader() throws Exception {
        String callbackUrl = "http://callback.example.com/status";
        RolloutImportConfigurationRequest request = new RolloutImportConfigurationRequest();

        mockMvc.perform(put(BASE_URL + SNAPSHOT_PATH, SNAPSHOT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .header(RolloutImportConstants.CALLBACK_URL_HEADER, callbackUrl))
                .andExpect(status().isAccepted());

        verify(rolloutImportService).processAsync(eq(SNAPSHOT_ID), any(RolloutImportConfigurationRequest.class), eq(callbackUrl));
    }
}

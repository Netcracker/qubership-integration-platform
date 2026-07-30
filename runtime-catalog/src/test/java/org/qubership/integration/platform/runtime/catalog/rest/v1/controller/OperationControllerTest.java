package org.qubership.integration.platform.runtime.catalog.rest.v1.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.runtime.catalog.exception.GlobalExceptionHandler;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.BadRequestException;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.Operation;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.OperationDTO;
import org.qubership.integration.platform.runtime.catalog.rest.v1.mapper.OperationMapper;
import org.qubership.integration.platform.runtime.catalog.rest.v1.mapper.OperationSchemasMapper;
import org.qubership.integration.platform.runtime.catalog.service.OperationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The controller passes the query through; {@code modelId} and {@code sortColumns} are validated in
 * {@code OperationService}, next to the Criteria query whose attribute names the allowlist mirrors. What is left
 * here is the web-facing half: a rejected request has to render as a 400 carrying the reason.
 */
@ExtendWith(MockitoExtension.class)
class OperationControllerTest {

    private static final String BASE_URL = "/v1/operations";
    private static final String MODEL_ID = "model-1";
    private static final List<String> SORT_COLUMNS = List.of("path", "method", "name");

    @Mock
    private OperationService operationService;

    @Mock
    private OperationMapper operationMapper;

    @Mock
    private OperationSchemasMapper operationSchemasMapper;

    @InjectMocks
    private OperationController operationController;

    @Captor
    private ArgumentCaptor<List<String>> sortColumnsCaptor;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(operationController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("GET /v1/operations renders a rejected query as 400 with the reason in the body")
    void rejectedQueryRendersAsBadRequest() throws Exception {
        when(operationService.getOperationsByModel(anyString(), anyInt(), anyInt(), anyString(), anyList()))
                .thenThrow(new BadRequestException("Unknown sort columns: active"));

        mockMvc.perform(get(BASE_URL).param("modelId", MODEL_ID).param("sortColumns", "active")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.errorMessage", containsString("active")));
    }

    @Test
    @DisplayName("GET /v1/operations without modelId is rejected before the controller runs")
    void missingModelIdIsRejected() throws Exception {
        mockMvc.perform(get(BASE_URL)).andExpect(status().isBadRequest());

        verifyNoInteractions(operationService);
    }

    /**
     * Spring trims the tokens of a single comma-separated value, but repeated parameters bind element by element and
     * keep their padding. That asymmetry is the reason {@code OperationService} trims at all, and only a real binding
     * can prove it still holds — a service-level test that hands over a hand-built list cannot.
     */
    @Test
    @DisplayName("Repeated sortColumns parameters reach the service with their padding intact")
    void repeatedSortColumnParametersKeepTheirPadding() throws Exception {
        List<Operation> operations = List.of(new Operation());
        when(operationService.getOperationsByModel(eq(MODEL_ID), anyInt(), anyInt(), anyString(), anyList()))
                .thenReturn(operations);
        when(operationMapper.toOperationDTOs(operations)).thenReturn(List.of(new OperationDTO()));

        mockMvc.perform(get(BASE_URL).param("modelId", MODEL_ID)
                        .param("sortColumns", "name")
                        .param("sortColumns", " path")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        verify(operationService)
                .getOperationsByModel(eq(MODEL_ID), anyInt(), anyInt(), anyString(), sortColumnsCaptor.capture());
        assertEquals(List.of("name", " path"), sortColumnsCaptor.getValue());
    }

    @Test
    @DisplayName("The query reaches the service unchanged")
    void passesTheQueryToTheService() {
        List<Operation> operations = List.of(new Operation());
        List<OperationDTO> dtos = List.of(new OperationDTO());
        when(operationService.getOperationsByModel(eq(MODEL_ID), anyInt(), anyInt(), anyString(), anyList()))
                .thenReturn(operations);
        when(operationMapper.toOperationDTOs(operations)).thenReturn(dtos);

        ResponseEntity<List<OperationDTO>> response =
                operationController.getOperationsByModel(MODEL_ID, 0, 20, "", SORT_COLUMNS);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(dtos, response.getBody());
    }
}

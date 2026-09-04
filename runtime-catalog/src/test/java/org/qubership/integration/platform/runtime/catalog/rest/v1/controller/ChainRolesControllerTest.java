package org.qubership.integration.platform.runtime.catalog.rest.v1.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.runtime.catalog.exception.GlobalExceptionHandler;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.AbacRoleChangeException;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.chain.UpdateRolesRequest;
import org.qubership.integration.platform.runtime.catalog.service.ChainRolesService;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.MethodValidationPostProcessor;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pins the HTTP contract rather than the return statement: the constraints on the request reach
 * the caller as a 400, and so do the exceptions the service throws. Dropping {@code @Validated}
 * from the controller, or the handler from {@code GlobalExceptionHandler}, fails these.
 */
@ExtendWith(MockitoExtension.class)
class ChainRolesControllerTest {

    private static final String ROLES_URL = "/v1/catalog/chains/roles";
    private static final String REDEPLOY_URL = "/v1/catalog/chains/roles/redeploy";
    private static final String ELEMENT_ID = "element-a";
    private static final String CHAIN_ID = "chain-a";

    @Mock
    private ChainRolesService chainRolesService;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        // The controller is @Validated, so in production its constraints run through an AOP proxy.
        MethodValidationPostProcessor validationPostProcessor = new MethodValidationPostProcessor();
        validationPostProcessor.afterPropertiesSet();
        Object controller = validationPostProcessor.postProcessAfterInitialization(
                new ChainRolesController(chainRolesService), "chainRolesController");

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("A role update answers 204 with no body")
    void updateRolesAnswersNoContent() throws Exception {
        mockMvc.perform(put(ROLES_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(body(List.of(updateRequest()))))
                .andExpect(status().isNoContent());

        verify(chainRolesService).updateRoles(anyList());
    }

    @Test
    @DisplayName("A role update without a roles list is rejected before the service is called")
    void updateRolesRejectsMissingRoles() throws Exception {
        mockMvc.perform(put(ROLES_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(body(List.of(Map.of("elementId", ELEMENT_ID)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorMessage", containsString("roles")))
                .andExpect(jsonPath("$.errorMessage", containsString("must not be null")));

        verify(chainRolesService, never()).updateRoles(anyList());
    }

    @Test
    @DisplayName("A role update without an element id is rejected before the service is called")
    void updateRolesRejectsBlankElementId() throws Exception {
        mockMvc.perform(put(ROLES_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(body(List.of(Map.of("roles", List.of("reader"))))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorMessage", containsString("elementId")))
                .andExpect(jsonPath("$.errorMessage", containsString("must not be blank")));

        verify(chainRolesService, never()).updateRoles(anyList());
    }

    @Test
    @DisplayName("A role update on an ABAC endpoint answers 400 naming the element")
    void updateRolesAnswersBadRequestForAbacElement() throws Exception {
        doThrow(new AbacRoleChangeException(ELEMENT_ID)).when(chainRolesService).updateRoles(anyList());

        mockMvc.perform(put(ROLES_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(body(List.of(updateRequest()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorMessage", containsString(ELEMENT_ID)));
    }

    @Test
    @DisplayName("A role update on an element that does not exist answers 404")
    void updateRolesAnswersNotFoundForMissingElement() throws Exception {
        doThrow(new EntityNotFoundException("Can't find chain element with id: " + ELEMENT_ID))
                .when(chainRolesService).updateRoles(anyList());

        mockMvc.perform(put(ROLES_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(body(List.of(updateRequest()))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorMessage", containsString(ELEMENT_ID)));
    }

    @Test
    @DisplayName("A redeploy takes a bare list of chain ids and answers 204")
    void bulkRedeployAnswersNoContent() throws Exception {
        mockMvc.perform(put(REDEPLOY_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(body(List.of(CHAIN_ID))))
                .andExpect(status().isNoContent());

        verify(chainRolesService).redeploy(List.of(CHAIN_ID));
    }

    @Test
    @DisplayName("A redeploy of a chain that does not exist answers 404")
    void bulkRedeployAnswersNotFoundForMissingChain() throws Exception {
        doThrow(new EntityNotFoundException("Can't find chain with id: " + CHAIN_ID))
                .when(chainRolesService).redeploy(anyList());

        mockMvc.perform(put(REDEPLOY_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(body(List.of(CHAIN_ID))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorMessage", containsString(CHAIN_ID)));
    }

    private UpdateRolesRequest updateRequest() {
        UpdateRolesRequest request = new UpdateRolesRequest();
        request.setElementId(ELEMENT_ID);
        request.setRoles(Set.of("reader"));
        return request;
    }

    private String body(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }
}

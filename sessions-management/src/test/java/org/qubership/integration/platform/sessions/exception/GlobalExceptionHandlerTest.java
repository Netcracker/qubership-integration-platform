package org.qubership.integration.platform.sessions.exception;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.sessions.controller.SessionController;
import org.qubership.integration.platform.sessions.service.CatalogInternalService;
import org.qubership.integration.platform.sessions.service.SessionService;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Every case here is a mistake a caller can make against the real session endpoints. Each one used
 * to answer 500, which reads in monitoring as a fault of the service rather than of the request.
 */
class GlobalExceptionHandlerTest {

    private static final String SEARCH_BODY = "{\"filterRequestList\":[],\"searchString\":\"\"}";

    private SessionService sessionService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        sessionService = mock(SessionService.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new SessionController(sessionService, mock(CatalogInternalService.class)))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("A malformed request body is a client error")
    void malformedBodyAnswersBadRequest() throws Exception {
        mockMvc.perform(post("/v1/sessions").contentType(MediaType.APPLICATION_JSON).content("{\"filterRequestList\":"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("A missing request body is a client error")
    void missingBodyAnswersBadRequest() throws Exception {
        mockMvc.perform(post("/v1/sessions").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("A body sent as the wrong media type is a client error")
    void wrongMediaTypeAnswersUnsupportedMediaType() throws Exception {
        mockMvc.perform(post("/v1/sessions").contentType(MediaType.TEXT_PLAIN).content(SEARCH_BODY))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    @DisplayName("A method the endpoint does not map is a client error")
    void unsupportedMethodAnswersMethodNotAllowed() throws Exception {
        mockMvc.perform(put("/v1/sessions").contentType(MediaType.APPLICATION_JSON).content(SEARCH_BODY))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    @DisplayName("A parameter that does not convert is a client error")
    void unconvertibleParameterAnswersBadRequest() throws Exception {
        mockMvc.perform(post("/v1/sessions").param("offset", "abc")
                        .contentType(MediaType.APPLICATION_JSON).content(SEARCH_BODY))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("A missing required parameter is a client error")
    void missingParameterAnswersBadRequest() throws Exception {
        mockMvc.perform(delete("/v1/sessions/chains"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("A session that does not exist is still reported as missing")
    void missingSessionKeepsItsStatus() throws Exception {
        when(sessionService.findById(anyString(), anyString(), any(Boolean.class), any(Boolean.class)))
                .thenReturn(null);

        mockMvc.perform(get("/v1/sessions/no-such-session"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorMessage").value("Can't find session no-such-session"));
    }

    @Test
    @DisplayName("A search the service rejects is still a client error with its own message")
    void rejectedSearchKeepsItsStatus() throws Exception {
        when(sessionService.getSessions(any(), anyInt(), anyInt(), anyString(), any()))
                .thenThrow(new SearchException("Can't sort results on this column"));

        mockMvc.perform(post("/v1/sessions").contentType(MediaType.APPLICATION_JSON).content(SEARCH_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorMessage").value("Can't sort results on this column"));
    }

    @Test
    @DisplayName("A fault that is not the caller's is still a server error")
    void unexpectedFailureStaysServerError() throws Exception {
        when(sessionService.getSessions(any(), anyInt(), anyInt(), anyString(), any()))
                .thenThrow(new IllegalStateException("opensearch is down"));

        mockMvc.perform(post("/v1/sessions").contentType(MediaType.APPLICATION_JSON).content(SEARCH_BODY))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.errorMessage").value("opensearch is down"));
    }
}

package org.qubership.integration.platform.runtime.catalog.rest.v3.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.runtime.catalog.configuration.MapperAutoConfiguration;
import org.qubership.integration.platform.runtime.catalog.exception.GlobalExceptionHandler;
import org.qubership.integration.platform.runtime.catalog.model.mapper.mapping.EntityDiffResponseMapper;
import org.qubership.integration.platform.runtime.catalog.rest.v1.mapper.ChainMapper;
import org.qubership.integration.platform.runtime.catalog.rest.v3.mapper.ImportSessionMapper;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.GeneralImportService;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ImportControllerV3Test {

    @Mock
    GeneralImportService importService;
    @Mock
    ImportSessionMapper importSessionMapper;
    @Mock
    EntityDiffResponseMapper entityDiffResponseMapper;
    @Mock
    ChainMapper chainMapper;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ImportControllerV3 controller = new ImportControllerV3(
                new MapperAutoConfiguration().primaryObjectMapper(),
                importService, importSessionMapper, entityDiffResponseMapper, chainMapper);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private ResultActions importWith(String importRequest) throws Exception {
        return mockMvc.perform(multipart("/v3/import")
                .file(new MockMultipartFile("file", "export.zip", "application/zip", new byte[0]))
                .param("importRequest", importRequest));
    }

    @Test
    void unknownDeployActionIsRejected() throws Exception {
        importWith("{\"chainCommitRequests\":[{\"id\":\"c1\",\"deployAction\":\"DEPLOOY\"}]}")
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("DEPLOOY")));

        verify(importService, never()).importFileAsync(any(), any(), any(), anyBoolean());
    }

    @Test
    void chainCommitRequestWithoutIdIsRejected() throws Exception {
        importWith("{\"chainCommitRequests\":[{\"deployAction\":\"NONE\"}]}")
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("must have an id")));

        verify(importService, never()).importFileAsync(any(), any(), any(), anyBoolean());
    }

    @Test
    void validRequestIsAccepted() throws Exception {
        importWith("{\"chainCommitRequests\":[{\"id\":\"c1\",\"deployAction\":\"deploy\","
                + "\"domains\":[{\"name\":\"default\"}]}]}")
                .andExpect(status().isAccepted());

        verify(importService).importFileAsync(any(), any(), any(), anyBoolean());
    }
}

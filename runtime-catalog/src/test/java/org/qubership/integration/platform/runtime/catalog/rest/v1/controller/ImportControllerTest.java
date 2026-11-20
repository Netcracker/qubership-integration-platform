package org.qubership.integration.platform.runtime.catalog.rest.v1.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.runtime.catalog.configuration.MapperAutoConfiguration;
import org.qubership.integration.platform.runtime.catalog.exception.GlobalExceptionHandler;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.ImportService;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ImportControllerTest {

    @Mock
    ImportService importService;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ImportController controller = new ImportController(importService, new MapperAutoConfiguration().primaryObjectMapper());
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static MockMultipartHttpServletRequestBuilder importRequest() {
        return multipart("/v1/catalog/import")
                .file(new MockMultipartFile("file", "export.zip", "application/zip", new byte[0]));
    }

    @Test
    void unknownDeployActionIsRejected() throws Exception {
        mockMvc.perform(importRequest().param("chainCommitRequests", "[{\"id\":\"c1\",\"deployAction\":\"DEPLOOY\"}]"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("DEPLOOY")));

        verify(importService, never()).importFile(any(), any(), any());
    }

    @Test
    void absentListReachesTheServiceAsNull() throws Exception {
        mockMvc.perform(importRequest()).andExpect(status().isOk());

        verify(importService).importFile(any(), isNull(), any());
    }

    @Test
    void emptyListReachesTheServiceAsEmpty() throws Exception {
        mockMvc.perform(importRequest().param("chainCommitRequests", "[]")).andExpect(status().isOk());

        verify(importService).importFile(any(), eq(List.of()), any());
    }
}

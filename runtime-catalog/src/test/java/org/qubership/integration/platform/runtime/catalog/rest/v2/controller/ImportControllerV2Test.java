package org.qubership.integration.platform.runtime.catalog.rest.v2.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.runtime.catalog.configuration.MapperAutoConfiguration;
import org.qubership.integration.platform.runtime.catalog.exception.GlobalExceptionHandler;
import org.qubership.integration.platform.runtime.catalog.rest.v2.mapper.ImportChainAsyncMapper;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.ImportService;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.ImportSessionService;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.ImportV2RedirectPathResolver;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.net.URI;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ImportControllerV2Test {

    @Mock
    ImportService importService;
    @Mock
    ImportSessionService importSessionService;
    @Mock
    ImportChainAsyncMapper importChainAsyncMapper;
    @Mock
    ImportV2RedirectPathResolver redirectPathResolver;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ImportControllerV2 controller = new ImportControllerV2(importService, importSessionService,
                new MapperAutoConfiguration().primaryObjectMapper(), importChainAsyncMapper, redirectPathResolver);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static MockMultipartHttpServletRequestBuilder importRequest() {
        return multipart("/v2/import")
                .file(new MockMultipartFile("file", "export.zip", "application/zip", new byte[0]));
    }

    private void redirectResolves() {
        when(redirectPathResolver.resolve(any(), any())).thenReturn(URI.create("/v2/import"));
    }

    @Test
    void unknownDeployActionIsRejected() throws Exception {
        mockMvc.perform(importRequest().param("chainCommitRequests", "[{\"id\":\"c1\",\"deployAction\":\"DEPLOOY\"}]"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("DEPLOOY")));

        verify(importService, never()).importFileAsync(any(), any(), any());
    }

    @Test
    void absentListReachesTheServiceAsNull() throws Exception {
        redirectResolves();

        mockMvc.perform(importRequest()).andExpect(status().isAccepted());

        verify(importService).importFileAsync(any(), isNull(), any());
    }

    @Test
    void emptyListReachesTheServiceAsEmpty() throws Exception {
        redirectResolves();

        mockMvc.perform(importRequest().param("chainCommitRequests", "[]")).andExpect(status().isAccepted());

        verify(importService).importFileAsync(any(), eq(List.of()), any());
    }
}

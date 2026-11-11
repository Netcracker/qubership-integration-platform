package org.qubership.integration.platform.runtime.catalog.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.runtime.catalog.rest.handler.RestTemplateMsResponseErrorHandler;
import org.qubership.integration.platform.runtime.catalog.rest.handler.exception.MicroserviceErrorResponseException;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class MicroserviceRestTemplateAutoConfigurationTest {

    private final RestTemplate restTemplate = new MicroserviceRestTemplateAutoConfiguration().restTemplateMSDev(
            new RestTemplateBuilder(), new RestTemplateMsResponseErrorHandler(new ObjectMapper()));

    @Test
    void restTemplateUsesOneMinuteTimeouts() {
        SimpleClientHttpRequestFactory factory = (SimpleClientHttpRequestFactory)
                ReflectionTestUtils.getField(restTemplate.getRequestFactory(), "requestFactory");
        assertEquals(60_000, ReflectionTestUtils.getField(factory, "connectTimeout"));
        assertEquals(60_000, ReflectionTestUtils.getField(factory, "readTimeout"));
    }

    @Test
    void restTemplateThrowsErrorMessageFromMicroserviceResponse() {
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(requestTo("http://engine/v1/deployments"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"errorMessage\":\"Deployment not found\"}"));

        MicroserviceErrorResponseException exception = assertThrows(MicroserviceErrorResponseException.class,
                () -> restTemplate.getForObject("http://engine/v1/deployments", String.class));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        assertEquals("Deployment not found", exception.getMessage());
    }
}

package org.qubership.integration.platform.runtime.catalog.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.runtime.catalog.rest.handler.RestTemplateMsResponseErrorHandler;
import org.qubership.integration.platform.runtime.catalog.rest.handler.exception.MicroserviceErrorResponseException;
import org.qubership.integration.platform.runtime.catalog.service.variables.secrets.KubeSecretSerializer;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

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

    @Test
    void bootConfiguredClientReturnsSpecificationBodiesAsString() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration.class,
                        org.springframework.boot.restclient.autoconfigure.RestTemplateAutoConfiguration.class))
                .withUserConfiguration(MapperAutoConfiguration.class, RestTemplateTestConfiguration.class)
                .withPropertyValues("cip.export.minimize-quotes=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    RestTemplate bootConfiguredTemplate = context.getBean("restTemplateMS", RestTemplate.class);
                    MockRestServiceServer server = MockRestServiceServer.bindTo(bootConfiguredTemplate).build();
                    String jsonBody = "{\"openapi\":\"3.1.0\"}";
                    String yamlBody = "openapi: 3.1.0";
                    String plainTextBody = "specification body";
                    server.expect(requestTo("http://service/json-specification"))
                            .andRespond(withSuccess(jsonBody, MediaType.APPLICATION_JSON));
                    server.expect(requestTo("http://service/yaml-specification"))
                            .andRespond(withSuccess(yamlBody, MediaType.parseMediaType("application/yaml")));
                    server.expect(requestTo("http://service/text-specification"))
                            .andRespond(withSuccess(plainTextBody, MediaType.TEXT_PLAIN));

                    assertEquals(jsonBody, bootConfiguredTemplate.getForObject(
                            "http://service/json-specification", String.class));
                    assertEquals(yamlBody, bootConfiguredTemplate.getForObject(
                            "http://service/yaml-specification", String.class));
                    assertEquals(plainTextBody, bootConfiguredTemplate.getForObject(
                            "http://service/text-specification", String.class));
                    server.verify();
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class RestTemplateTestConfiguration {

        @Bean
        KubeSecretSerializer kubeSecretSerializer() {
            return new KubeSecretSerializer();
        }

        @Bean("restTemplateMS")
        RestTemplate restTemplateMS(RestTemplateBuilder builder) {
            return new MicroserviceRestTemplateAutoConfiguration().restTemplateMSDev(
                    builder, new RestTemplateMsResponseErrorHandler(new ObjectMapper()));
        }
    }
}

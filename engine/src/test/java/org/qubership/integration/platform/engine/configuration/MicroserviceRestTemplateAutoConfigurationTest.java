package org.qubership.integration.platform.engine.configuration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MicroserviceRestTemplateAutoConfigurationTest {

    private final MicroserviceRestTemplateAutoConfiguration configuration =
            new MicroserviceRestTemplateAutoConfiguration(12_345);

    @Test
    void restTemplateUsesConfiguredTimeout() {
        RestTemplate restTemplate = configuration.restTemplateMS(new RestTemplateBuilder());

        assertTimeouts(restTemplate, 12_345);
    }

    @Test
    void consulRestTemplateUsesConsulTimeout() {
        RestTemplate restTemplate = configuration.consulRestTemplateMS(new RestTemplateBuilder());

        assertTimeouts(restTemplate, 25_000);
    }

    private static void assertTimeouts(RestTemplate restTemplate, int expectedMillis) {
        SimpleClientHttpRequestFactory factory = (SimpleClientHttpRequestFactory)
                ReflectionTestUtils.getField(restTemplate.getRequestFactory(), "requestFactory");

        assertEquals(expectedMillis, ReflectionTestUtils.getField(factory, "connectTimeout"));
        assertEquals(expectedMillis, ReflectionTestUtils.getField(factory, "readTimeout"));
    }
}

package org.qubership.integration.platform.runtime.catalog.configuration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RestTemplateAutoConfigurationTest {

    @Test
    void restTemplateUsesOneMinuteTimeouts() {
        RestTemplate restTemplate = new RestTemplateAutoConfiguration().restTemplateMSDev(new RestTemplateBuilder());

        SimpleClientHttpRequestFactory factory = (SimpleClientHttpRequestFactory)
                ReflectionTestUtils.getField(restTemplate.getRequestFactory(), "requestFactory");
        assertEquals(60_000, ReflectionTestUtils.getField(factory, "connectTimeout"));
        assertEquals(60_000, ReflectionTestUtils.getField(factory, "readTimeout"));
    }
}

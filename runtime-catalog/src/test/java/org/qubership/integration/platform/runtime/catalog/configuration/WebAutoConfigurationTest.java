package org.qubership.integration.platform.runtime.catalog.configuration;

import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.folder.StringToFolderContentFilterConverter;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.ResourceHttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class WebAutoConfigurationTest {

    @Test
    void customMapperConverterStaysInMvcAfterBinaryConverters() {
        JsonMapper primaryObjectMapper = JsonMapper.builder().build();
        WebAutoConfiguration configuration = new WebAutoConfiguration(
                new StringToFolderContentFilterConverter(primaryObjectMapper), primaryObjectMapper);
        List<HttpMessageConverter<?>> converters = new ArrayList<>(List.of(
                new StringHttpMessageConverter(),
                new ByteArrayHttpMessageConverter(),
                new ResourceHttpMessageConverter()));

        configuration.extendMessageConverters(converters);

        assertInstanceOf(ByteArrayHttpMessageConverter.class, converters.get(0));
        assertInstanceOf(ResourceHttpMessageConverter.class, converters.get(1));
        JacksonJsonHttpMessageConverter mapperConverter =
                assertInstanceOf(JacksonJsonHttpMessageConverter.class, converters.get(2));
        assertEquals(4, converters.size());
    }
}

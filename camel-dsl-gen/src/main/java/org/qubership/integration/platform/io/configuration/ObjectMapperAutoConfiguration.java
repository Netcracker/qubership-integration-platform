package org.qubership.integration.platform.io.configuration;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.ser.impl.SimpleFilterProvider;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
public class ObjectMapperAutoConfiguration {
    @Bean("primaryObjectMapper")
    @ConditionalOnMissingBean(name = "primaryObjectMapper")
    public ObjectMapper qipPrimaryObjectMapper() {
        ObjectMapper objectMapper = JsonMapper.builder()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
            .enable(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(DeserializationFeature.FAIL_ON_INVALID_SUBTYPE)
            .disable(MapperFeature.DEFAULT_VIEW_INCLUSION)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        objectMapper.setFilterProvider(new SimpleFilterProvider().setFailOnUnknownId(false));
        return objectMapper;
    }
}

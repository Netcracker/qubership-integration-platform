package org.qubership.integration.platform.io.configuration;

import io.swagger.v3.core.util.Json;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.ser.std.SimpleFilterProvider;


@AutoConfiguration
public class ObjectMapperAutoConfiguration {
    @Primary
    @Bean("primaryObjectMapper")
    @ConditionalOnMissingBean(name = "primaryObjectMapper")
    public ObjectMapper primaryObjectMapper() {
        return JsonMapper.builder()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
            .enable(EnumFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(DeserializationFeature.FAIL_ON_INVALID_SUBTYPE)
            .disable(MapperFeature.DEFAULT_VIEW_INCLUSION)
            .changeDefaultPropertyInclusion(MapperInclusion::nonNull)
            .filterProvider(new SimpleFilterProvider().setFailOnUnknownId(false))
            .build();
    }

    /**
     * The swagger-core OpenAPI mapper. The Swagger specification parser serializes schemas with it,
     * so it must be the swagger-core mapper rather than the primary one.
     */
    @Bean("openApiObjectMapper")
    @ConditionalOnMissingBean(name = "openApiObjectMapper")
    public com.fasterxml.jackson.databind.ObjectMapper openApiObjectMapper() {
        return Json.mapper();
    }
}

/*
 * Copyright 2024-2025 NetCracker Technology Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.qubership.integration.platform.catalog.configuration;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.impl.SimpleFilterProvider;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.swagger.v3.core.util.Json;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.yaml.snakeyaml.LoaderOptions;

@Configuration
public class MapperBaseConfiguration {
    private static final int CODE_POINT_LIMIT_MB = 256;

    @Primary
    @Bean(name = {"objectMapper", "jsonMapper"})
    @ConditionalOnProperty(prefix = "app", name = "prefix", havingValue = "qip")
    public ObjectMapper objectMapper() {
        return qipPrimaryObjectMapper();
    }

    @Bean("primaryObjectMapper")
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

    @Bean("defaultYamlMapper")
    public YAMLMapper defaultYamlMapper() {
        return new YAMLMapper(createCustomYamlFactory());
    }

    @Bean("openApiObjectMapper")
    public ObjectMapper openApiObjectMapper() {
        return Json.mapper();
    }

    @Bean("specYamlMapper")
    public YAMLMapper specYamlMapper() {
        YAMLMapper yamlMapper = new YAMLMapper(createCustomYamlFactory());
        SimpleModule serializeModule = new SimpleModule();
        yamlMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        yamlMapper.registerModule(serializeModule);
        yamlMapper.setFilterProvider(new SimpleFilterProvider().setFailOnUnknownId(false));
        yamlMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        return yamlMapper;
    }

    private YAMLFactory createCustomYamlFactory() {
        LoaderOptions loaderOptions = new LoaderOptions();
        loaderOptions.setCodePointLimit(CODE_POINT_LIMIT_MB * 1024 * 1024);
        return YAMLFactory.builder().loaderOptions(loaderOptions).build();
    }
}

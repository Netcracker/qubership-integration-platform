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

package org.qubership.integration.platform.variables.management.configuration;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.impl.SimpleBeanPropertyFilter;
import com.fasterxml.jackson.databind.ser.impl.SimpleFilterProvider;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.kubernetes.client.openapi.models.V1Secret;
import org.qubership.integration.platform.variables.management.service.serializer.KubeSecretSerializer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@AutoConfiguration
public class MapperAutoConfiguration {

    @Bean
    @Primary
    @ConditionalOnProperty(prefix = "app", name = "prefix", havingValue = "qip")
    public ObjectMapper objectMapper() {
        return qipPrimaryObjectMapper();
    }

    @Bean("primaryObjectMapper")
    public ObjectMapper qipPrimaryObjectMapper() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        objectMapper.disable(MapperFeature.DEFAULT_VIEW_INCLUSION);
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        objectMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
        objectMapper.enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS);
        objectMapper.setFilterProvider(new SimpleFilterProvider().setFailOnUnknownId(false));

        return objectMapper;
    }

    @Bean("objectMapperWithSorting")
    public ObjectMapper objectMapperWithSorting() {
        ObjectMapper objectMapper = qipPrimaryObjectMapper();
        objectMapper.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

        return objectMapper;
    }

    @Bean("yamlMapper")
    public YAMLMapper yamlMapper(KubeSecretSerializer kubeSecretSerializer) {
        YAMLMapper yamlMapper = new YAMLMapper();
        SimpleModule serializeModule = new SimpleModule();
        serializeModule.addSerializer(V1Secret.class, kubeSecretSerializer);

        yamlMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        yamlMapper.registerModule(serializeModule);
        yamlMapper.setFilterProvider(new SimpleFilterProvider().setFailOnUnknownId(false));
        yamlMapper.enable(YAMLGenerator.Feature.MINIMIZE_QUOTES);
        yamlMapper.enable(JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES);

        return yamlMapper;
    }

    @Bean("yamlImportExportMapper")
    public YAMLMapper yamlImportExportMapper() {
        final String[] excludedFields = {"createdWhen", "modifiedWhen", "createdBy", "modifiedBy"};

        YAMLMapper yamlMapper = new YAMLMapper();
        SimpleModule serializeModule = new SimpleModule();

        yamlMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        yamlMapper.registerModule(serializeModule);
        SimpleFilterProvider simpleFilterProvider = new SimpleFilterProvider().setFailOnUnknownId(false);
        simpleFilterProvider.addFilter("commonVariableFilter",
                SimpleBeanPropertyFilter.serializeAllExcept(excludedFields));
        yamlMapper.setFilterProvider(simpleFilterProvider);
        yamlMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        return yamlMapper;
    }
}

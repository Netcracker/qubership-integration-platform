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

package org.qubership.integration.platform.runtime.catalog.configuration;

import io.kubernetes.client.openapi.models.V1Secret;
import org.qubership.integration.platform.io.configuration.MapperInclusion;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.service.codeview.deserializer.CodeviewChainElementDeserializer;
import org.qubership.integration.platform.runtime.catalog.service.codeview.serializer.CodeviewChainElementSerializer;
import org.qubership.integration.platform.runtime.catalog.service.variables.secrets.KubeSecretSerializer;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
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
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.SimpleBeanPropertyFilter;
import tools.jackson.databind.ser.std.SimpleFilterProvider;
import tools.jackson.dataformat.yaml.YAMLFactory;
import tools.jackson.dataformat.yaml.YAMLMapper;
import tools.jackson.dataformat.yaml.YAMLWriteFeature;


@AutoConfiguration
public class MapperAutoConfiguration {
    private static final int CODE_POINT_LIMIT_MB = 256;

    @Primary
    @Bean(name = {"objectMapper", "jsonMapper"})
    @ConditionalOnMissingBean(name = {"objectMapper", "jsonMapper"})
    public ObjectMapper objectMapper() {
        return primaryObjectMapper();
    }

    @Bean("primaryObjectMapper")
    public JsonMapper primaryObjectMapper() {
        return JsonMapper.builder()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
                .enable(EnumFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(DeserializationFeature.FAIL_ON_INVALID_SUBTYPE)
                .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .disable(MapperFeature.DEFAULT_VIEW_INCLUSION)
                .changeDefaultPropertyInclusion(MapperInclusion::nonNull)
                .filterProvider(new SimpleFilterProvider().setFailOnUnknownId(false))
                .build();
    }

    @Bean("yamlMapper")
    public YAMLMapper yamlMapper(KubeSecretSerializer kubeSecretSerializer) {
        SimpleModule serializeModule = new SimpleModule();
        serializeModule.addSerializer(V1Secret.class, kubeSecretSerializer);
        return YAMLMapper.builder(createCustomYamlFactory())
                .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .changeDefaultPropertyInclusion(MapperInclusion::nonNull)
                .addModule(serializeModule)
                .filterProvider(new SimpleFilterProvider().setFailOnUnknownId(false))
                .build();
    }

    @Bean("openApiObjectMapper")
    public ObjectMapper openApiObjectMapper() {
        return JsonMapper.builder()
                .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .build();
    }

    @Bean("defaultYamlMapper")
    public YAMLMapper defaultYamlMapper() {
        return YAMLMapper.builder(createCustomYamlFactory())
                .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .build();
    }

    @Bean("yamlExportImportMapper")
    public YAMLMapper yamlExportImportMapper(@Value("${cip.export.minimize-quotes}") boolean minimizeQuotes) {
        final String[] excludedFields = {
                "createdWhen",
                "createdBy",
                "modifiedBy",
                "classifier",
                "classifierV3",
                "status",
                "sourceHash"
        };

        SimpleModule serializeModule = new SimpleModule();
        SimpleFilterProvider simpleFilterProvider = new SimpleFilterProvider().setFailOnUnknownId(false);
        simpleFilterProvider.addFilter("baseEntityFilter",
                SimpleBeanPropertyFilter.serializeAllExcept(excludedFields));
        return YAMLMapper.builder(createCustomYamlFactory())
                .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .changeDefaultPropertyInclusion(MapperInclusion::nonNull)
                .addModule(serializeModule)
                .filterProvider(simpleFilterProvider)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .configure(YAMLWriteFeature.MINIMIZE_QUOTES, minimizeQuotes)
                .build();
    }

    @Bean("codeViewYamlMapper")
    public YAMLMapper codeViewYamlMapper(@Qualifier("primaryObjectMapper") ObjectMapper objectMapper) {
        SimpleModule serializeModule = new SimpleModule();
        serializeModule.addSerializer(ChainElement.class, new CodeviewChainElementSerializer());
        serializeModule.addDeserializer(ChainElement.class, new CodeviewChainElementDeserializer(objectMapper));
        return YAMLMapper.builder(createCustomYamlFactory())
                .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .changeDefaultPropertyInclusion(MapperInclusion::nonNull)
                .addModule(serializeModule)
                .build();
    }

    @Bean("objectMapperWithSorting")
    public ObjectMapper objectMapperWithSorting() {
        return primaryObjectMapper().rebuild()
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .build();
    }

    @Bean("variablesYamlMapper")
    public YAMLMapper variablesYamlMapper(KubeSecretSerializer kubeSecretSerializer) {
        SimpleModule serializeModule = new SimpleModule();
        serializeModule.addSerializer(V1Secret.class, kubeSecretSerializer);
        return YAMLMapper.builder(createCustomYamlFactory())
                .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .changeDefaultPropertyInclusion(MapperInclusion::nonNull)
                .addModule(serializeModule)
                .filterProvider(new SimpleFilterProvider().setFailOnUnknownId(false))
                .enable(YAMLWriteFeature.MINIMIZE_QUOTES)
                .build();
    }

    @Bean("variablesYamlImportExportMapper")
    public YAMLMapper variablesYamlImportExportMapper() {
        final String[] excludedFields = {"createdWhen", "modifiedWhen", "createdBy", "modifiedBy"};

        SimpleModule serializeModule = new SimpleModule();
        SimpleFilterProvider simpleFilterProvider = new SimpleFilterProvider().setFailOnUnknownId(false);
        simpleFilterProvider.addFilter("commonVariableFilter",
                SimpleBeanPropertyFilter.serializeAllExcept(excludedFields));
        return YAMLMapper.builder()
                .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .changeDefaultPropertyInclusion(MapperInclusion::nonNull)
                .addModule(serializeModule)
                .filterProvider(simpleFilterProvider)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
    }

    private YAMLFactory createCustomYamlFactory() {
        LoadSettings loadSettings = LoadSettings.builder()
                .setCodePointLimit(CODE_POINT_LIMIT_MB * 1024 * 1024)
                .build();
        return new MinimizedQuotesYamlFactory(YAMLFactory.builder()
                .loadSettings(loadSettings)
                .stringQuotingChecker(new YamlStringQuotingChecker()));
    }

}

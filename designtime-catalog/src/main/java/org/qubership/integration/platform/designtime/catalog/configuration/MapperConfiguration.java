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

package org.qubership.integration.platform.designtime.catalog.configuration;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.impl.SimpleFilterProvider;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.qubership.integration.platform.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.designtime.catalog.service.codeview.deserializer.CodeviewChainElementDeserializer;
import org.qubership.integration.platform.designtime.catalog.service.codeview.serializer.CodeviewChainElementSerializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.yaml.snakeyaml.LoaderOptions;

@Configuration
public class MapperConfiguration {

    private static final int CODE_POINT_LIMIT_MB = 256;

    @Bean("yamlMapper")
    public YAMLMapper yamlMapper() {
        YAMLMapper yamlMapper = new YAMLMapper(createCustomYamlFactory());
        SimpleModule serializeModule = new SimpleModule();
        yamlMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        yamlMapper.registerModule(serializeModule);
        yamlMapper.setFilterProvider(new SimpleFilterProvider().setFailOnUnknownId(false));

        return yamlMapper;
    }

    @Bean("codeViewYamlMapper")
    public YAMLMapper codeViewYamlMapper(ObjectMapper objectMapper) {
        YAMLMapper yamlMapper = new YAMLMapper(createCustomYamlFactory());
        SimpleModule serializeModule = new SimpleModule();
        yamlMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        serializeModule.addSerializer(ChainElement.class, new CodeviewChainElementSerializer());
        serializeModule.addDeserializer(ChainElement.class, new CodeviewChainElementDeserializer(objectMapper));
        yamlMapper.registerModule(serializeModule);

        return yamlMapper;
    }

    private YAMLFactory createCustomYamlFactory() {
        LoaderOptions loaderOptions = new LoaderOptions();
        loaderOptions.setCodePointLimit(CODE_POINT_LIMIT_MB * 1024 * 1024);
        return YAMLFactory.builder().loaderOptions(loaderOptions).build();
    }
}

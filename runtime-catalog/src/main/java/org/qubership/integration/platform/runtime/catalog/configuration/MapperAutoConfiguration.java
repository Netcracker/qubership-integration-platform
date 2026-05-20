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

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.impl.SimpleBeanPropertyFilter;
import com.fasterxml.jackson.databind.ser.impl.SimpleFilterProvider;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.qubership.integration.platform.catalog.persistence.configs.entity.chain.Chain;
import org.qubership.integration.platform.catalog.persistence.configs.entity.chain.Dependency;
import org.qubership.integration.platform.catalog.persistence.configs.entity.chain.MaskedField;
import org.qubership.integration.platform.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.deserializer.ChainDeserializer;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.deserializer.ChainElementDeserializer;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.entity.ChainDeserializationResult;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.entity.ElementDeserializationResult;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.serializer.ChainElementSerializer;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.serializer.ChainSerializer;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.serializer.DependencySerializer;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.serializer.MaskedFieldSerializer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.yaml.snakeyaml.LoaderOptions;

@AutoConfiguration
public class MapperAutoConfiguration {
    private static final int CODE_POINT_LIMIT_MB = 256;

    @Bean("yamlMapper")
    public YAMLMapper yamlMapper(ChainDeserializer chainDeserializer,
                                 ChainElementDeserializer chainElementDeserializer) {
        YAMLMapper yamlMapper = new YAMLMapper(createCustomYamlFactory());
        SimpleModule serializeModule = new SimpleModule();
        yamlMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        serializeModule.addSerializer(Chain.class, new ChainSerializer());
        serializeModule.addSerializer(ChainElement.class, new ChainElementSerializer());
        serializeModule.addSerializer(Dependency.class, new DependencySerializer());
        serializeModule.addSerializer(MaskedField.class, new MaskedFieldSerializer());

        serializeModule.addDeserializer(ChainDeserializationResult.class, chainDeserializer);
        serializeModule.addDeserializer(ElementDeserializationResult.class, chainElementDeserializer);
        yamlMapper.registerModule(serializeModule);
        yamlMapper.setFilterProvider(new SimpleFilterProvider().setFailOnUnknownId(false));

        return yamlMapper;
    }

    @Bean("yamlExportImportMapper")
    public YAMLMapper yamlExportImportMapper() {
        final String[] excludedFields = {
                "createdWhen",
                "createdBy",
                "modifiedBy",
                "classifier",
                "classifierV3",
                "status",
                "sourceHash"
        };

        YAMLMapper yamlMapper = new YAMLMapper(createCustomYamlFactory());

        SimpleModule serializeModule = new SimpleModule();

        yamlMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        yamlMapper.registerModule(serializeModule);
        SimpleFilterProvider simpleFilterProvider = new SimpleFilterProvider().setFailOnUnknownId(false);
        simpleFilterProvider.addFilter("baseEntityFilter",
                SimpleBeanPropertyFilter.serializeAllExcept(excludedFields));
        yamlMapper.setFilterProvider(simpleFilterProvider);
        yamlMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        return yamlMapper;
    }

    private YAMLFactory createCustomYamlFactory() {
        LoaderOptions loaderOptions = new LoaderOptions();
        loaderOptions.setCodePointLimit(CODE_POINT_LIMIT_MB * 1024 * 1024);
        return YAMLFactory.builder().loaderOptions(loaderOptions).build();
    }
}

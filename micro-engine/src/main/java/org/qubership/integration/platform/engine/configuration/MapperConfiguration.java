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

package org.qubership.integration.platform.engine.configuration;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.rabbitmq.client.LongString;
import org.jetbrains.annotations.NotNull;
import org.postgresql.jdbc.PgArray;
import org.qubership.integration.platform.engine.camel.components.rabbitmq.serializers.LongStringSerializer;
import org.qubership.integration.platform.engine.camel.processors.serializers.PgArraySerializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MapperConfiguration {
    @Bean(name = {"objectMapper", "jsonMapper"})
    public ObjectMapper objectMapper() {
        return buildObjectMapper();
    }

    @Bean("checkpointMapper")
    public ObjectMapper checkpointMapper() {
        ObjectMapper mapper = buildObjectMapper();
        mapper.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
        return mapper;
    }

    @NotNull
    private static ObjectMapper buildObjectMapper() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
        objectMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);

        SimpleModule serializeModule = new SimpleModule();
        serializeModule.addSerializer(LongString.class, new LongStringSerializer(LongString.class));
        serializeModule.addSerializer(PgArray.class, new PgArraySerializer(PgArray.class));

        objectMapper.registerModule(serializeModule);
        return objectMapper;
    }
}

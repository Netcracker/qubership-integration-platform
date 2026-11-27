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
import com.rabbitmq.client.LongString;
import org.jetbrains.annotations.NotNull;
import org.postgresql.jdbc.PgArray;
import org.qubership.integration.platform.engine.camel.components.rabbitmq.serializers.LongStringSerializer;
import org.qubership.integration.platform.engine.camel.processors.serializers.PgArraySerializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;




@Configuration
public class MapperConfiguration {
    @Bean(name = {"objectMapper", "jsonMapper"})
    public ObjectMapper objectMapper() {
        return buildObjectMapper();
    }

    @Bean("checkpointMapper")
    public ObjectMapper checkpointMapper() {
        return buildObjectMapper(true);
    }

    @NotNull
    private static ObjectMapper buildObjectMapper() {
        SimpleModule serializeModule = new SimpleModule();
        serializeModule.addSerializer(LongString.class, new LongStringSerializer(LongString.class));
        serializeModule.addSerializer(PgArray.class, new PgArraySerializer(PgArray.class));
        return buildObjectMapper(false, serializeModule);
    }

    private static ObjectMapper buildObjectMapper(boolean allowEmptyBeans) {
        SimpleModule serializeModule = new SimpleModule();
        serializeModule.addSerializer(LongString.class, new LongStringSerializer(LongString.class));
        serializeModule.addSerializer(PgArray.class, new PgArraySerializer(PgArray.class));
        return buildObjectMapper(allowEmptyBeans, serializeModule);
    }

    private static ObjectMapper buildObjectMapper(boolean allowEmptyBeans, SimpleModule serializeModule) {
        JsonMapper.Builder builder = JsonMapper.builder()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(SerializationFeature.INDENT_OUTPUT)
                .changeDefaultPropertyInclusion(inclusion -> inclusion
                        .withValueInclusion(JsonInclude.Include.NON_NULL)
                        .withContentInclusion(JsonInclude.Include.NON_NULL))
                .addModule(serializeModule);
        if (allowEmptyBeans) {
            builder.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
        }
        return builder.build();
    }
}

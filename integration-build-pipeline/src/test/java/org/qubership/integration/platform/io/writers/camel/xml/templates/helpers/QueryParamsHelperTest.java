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

package org.qubership.integration.platform.io.writers.camel.xml.templates.helpers;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.github.jknack.handlebars.Context;
import com.github.jknack.handlebars.Options;
import com.github.jknack.handlebars.TagType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.qubership.integration.platform.chain.impl.ElementBuilder;
import org.qubership.integration.platform.chain.model.Element;
import org.qubership.integration.platform.io.writers.camel.xml.templates.TemplateService;
import org.qubership.integration.platform.library.components.LibraryElementsService;
import org.qubership.integration.platform.library.components.LibraryResourceLoader;
import org.qubership.integration.platform.library.configuration.DescriptorPropertiesConfiguration;
import org.qubership.integration.platform.mapper.build.MappingDescriptionValidator;
import org.qubership.integration.platform.mapper.build.atlasmap.AtlasMapInterpreter;
import org.qubership.integration.platform.testutils.configuration.TestConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.io.IOException;
import java.util.stream.Stream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.IsEqual.equalTo;

@ContextConfiguration(classes = {
        AtlasMapInterpreter.class,
        TestConfig.class,
        DescriptorPropertiesConfiguration.class,
        YAMLMapper.class,
        LibraryElementsService.class,
        TemplateService.class,
        LibraryResourceLoader.class,
        QueryParamsHelper.class,
        MappingDescriptionValidator.class
})
@ExtendWith(SpringExtension.class)
public class QueryParamsHelperTest {

    @Autowired
    private LibraryResourceLoader libraryResourceLoader;
    @Autowired
    private QueryParamsHelper helper;

    private final ObjectMapper objectMapper = new ObjectMapper();


    private static Stream<Arguments> queryParamsTestData() {
        return Stream.of(
                Arguments.of(
                        "kafka-sender-2",
                        """
                           {
                              "brokers": "kafka:9092",
                              "securityProtocol": "SASL_PLAINTEXT",
                              "saslMechanism": "SCRAM-SHA-512"
                           }
                        """,
                        "?brokers=kafka:9092&securityProtocol=SASL_PLAINTEXT&saslMechanism=SCRAM-SHA-512"
                ),
                Arguments.of(
                        "mail-sender",
                        """
                           {
                              "from": "payment-service@test.com",
                              "to": [ "admin@test.com", "client@test.com", "hd@test.com" ]
                           }
                        """,
                        "?from=payment-service@test.com&to=admin@test.com,client@test.com,hd@test.com"
                )
        );
    }

    @DisplayName("Composing query parameters")
    @ParameterizedTest(name = "#{index} => {0}")
    @MethodSource("queryParamsTestData")
    public void queryParamsTest(String elementType, String elementProperties, String expected) throws IOException {
        Element element = ElementBuilder.createNew()
            .type(elementType)
            .properties(objectMapper.readValue(elementProperties, new TypeReference<>() {}))
            .build();
        Options options = new Options
                .Builder(null, "query", TagType.VAR, Context.newContext(element), null)
                .build();

        String actual = String.valueOf(helper.apply(element, options));

        assertThat(actual, equalTo(expected));
    }
}

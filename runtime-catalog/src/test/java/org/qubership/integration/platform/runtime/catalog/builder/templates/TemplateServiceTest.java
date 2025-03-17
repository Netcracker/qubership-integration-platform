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

package org.qubership.integration.platform.runtime.catalog.builder.templates;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.catalog.configuration.MapperBaseConfiguration;
import org.qubership.integration.platform.catalog.configuration.element.descriptor.DescriptorPropertiesConfiguration;
import org.qubership.integration.platform.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.catalog.service.library.LibraryElementsService;
import org.qubership.integration.platform.catalog.service.library.LibraryResourceLoader;
import org.qubership.integration.platform.runtime.catalog.mapper.MappingDescriptionValidator;
import org.qubership.integration.platform.runtime.catalog.mapper.atlasmap.AtlasMapInterpreter;
import org.qubership.integration.platform.runtime.catalog.testutils.TestUtils;
import org.qubership.integration.platform.runtime.catalog.testutils.configuration.TestConfig;
import org.qubership.integration.platform.runtime.catalog.testutils.dto.ChainImportDTO;
import org.qubership.integration.platform.runtime.catalog.testutils.mapper.ChainElementsMapper;
import org.qubership.integration.platform.runtime.catalog.testutils.mapper.ChainMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.xmlunit.matchers.CompareMatcher;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

@ContextConfiguration(
        classes = {
                TestConfig.class,
                MapperBaseConfiguration.class,
                DescriptorPropertiesConfiguration.class,
                AtlasMapInterpreter.class,
                LibraryElementsService.class,
                LibraryResourceLoader.class,
                TemplateService.class,
                ChainElementsMapper.class,
                ChainMapper.class,
                MappingDescriptionValidator.class
        }
)
@TestPropertySource(properties = {
        "qip.gateway.egress.protocol=http",
        "qip.gateway.egress.url=egress-gateway:8080"
})
@ExtendWith(SpringExtension.class)
@ExtendWith(MockitoExtension.class)
public class TemplateServiceTest {

    private static MockedStatic<UUID> mockedUUID;

    private final ObjectMapper objectMapper;
    private final TemplateService templateService;
    private final ChainMapper chainMapper;

    @Autowired
    public TemplateServiceTest(
            @Qualifier("primaryObjectMapper") ObjectMapper objectMapper,
            TemplateService templateService,
            ChainMapper chainMapper
    ) {
        this.objectMapper = objectMapper;
        this.templateService = templateService;
        this.chainMapper = chainMapper;
    }

    @BeforeAll
    public static void initializeBeforeAll() {
        mockedUUID = mockStatic(UUID.class, CALLS_REAL_METHODS);
        UUID mockValue = UUID.fromString("dcfd578c-9321-4e9c-b000-6c19550bc6c5");
        mockedUUID.when(UUID::randomUUID).thenReturn(mockValue);
    }

    @AfterAll
    public static void finalizeAfterAll() {
        mockedUUID.close();
    }

    private static Stream<Arguments> applyTemplateTestData() {
        return Stream.of(
                Arguments.of(
                        "Condition element",
                        "/testData/input/builder/templates/condition.yml",
                        "/testData/output/builder/templates/condition.xml"
                ),
                Arguments.of(
                        "Async API Trigger element",
                        "/testData/input/builder/templates/async_api_trigger.yml",
                        "/testData/output/builder/templates/async_api_trigger.xml"
                ),
                Arguments.of(
                        "Chain Call 2 element",
                        "/testData/input/builder/templates/chain_call_2.yml",
                        "/testData/output/builder/templates/chain_call_2.xml"
                ),
                Arguments.of(
                        "Chain Trigger 2 element",
                        "/testData/input/builder/templates/chain_trigger_2.yml",
                        "/testData/output/builder/templates/chain_trigger_2.xml"
                ),
                Arguments.of(
                        "Circuit Breaker-2 element",
                        "/testData/input/builder/templates/circuit_breaker_2.yml",
                        "/testData/output/builder/templates/circuit_breaker_2.xml"
                ),
                Arguments.of(
                        "File Read element",
                        "/testData/input/builder/templates/file_read.yml",
                        "/testData/output/builder/templates/file_read.xml"
                ),
                Arguments.of(
                        "File Write element",
                        "/testData/input/builder/templates/file_write.yml",
                        "/testData/output/builder/templates/file_write.xml"
                ),
                Arguments.of(
                        "GraphQL Sender element",
                        "/testData/input/builder/templates/graphql_sender.yml",
                        "/testData/output/builder/templates/graphql_sender.xml"
                ),
                Arguments.of(
                        "External HTTP Sender element",
                        "/testData/input/builder/templates/http_sender_external.yml",
                        "/testData/output/builder/templates/http_sender_external.xml"
                ),
                Arguments.of(
                        "Internal HTTP Sender element",
                        "/testData/input/builder/templates/http_sender_internal.yml",
                        "/testData/output/builder/templates/http_sender_internal.xml"
                ),
                Arguments.of(
                        "Custom HTTP Trigger element",
                        "/testData/input/builder/templates/http_trigger_custom.yml",
                        "/testData/output/builder/templates/http_trigger_custom.xml"
                ),
                Arguments.of(
                        "Implemented Service HTTP Trigger element",
                        "/testData/input/builder/templates/http_trigger_implemented_service.yml",
                        "/testData/output/builder/templates/http_trigger_implemented_service.xml"
                ),
                Arguments.of(
                        "Kafka Trigger 2 element",
                        "/testData/input/builder/templates/kafka_trigger_2.yml",
                        "/testData/output/builder/templates/kafka_trigger_2.xml"
                ),
                Arguments.of(
                        "Kafka Sender 2 element",
                        "/testData/input/builder/templates/kafka_sender_2.yml",
                        "/testData/output/builder/templates/kafka_sender_2.xml"
                ),
                Arguments.of(
                        "Loop-2 element",
                        "/testData/input/builder/templates/loop_2.yml",
                        "/testData/output/builder/templates/loop_2.xml"
                ),
                Arguments.of(
                        "Mail Sender element",
                        "/testData/input/builder/templates/mail_sender.yml",
                        "/testData/output/builder/templates/mail_sender.xml"
                ),
                // TODO: update test data
//                Arguments.of(
//                        "Mapper-2 element",
//                        "/testData/input/builder/templates/mapper_2.yml",
//                        "/testData/output/builder/templates/mapper_2.xml"
//                ),
                Arguments.of(
                        "Quartz Scheduler element",
                        "/testData/input/builder/templates/quartz_scheduler.yml",
                        "/testData/output/builder/templates/quartz_scheduler.xml"
                ),
                Arguments.of(
                        "RabbitMQ Trigger 2 element",
                        "/testData/input/builder/templates/rabbitmq_trigger_2.yml",
                        "/testData/output/builder/templates/rabbitmq_trigger_2.xml"
                ),
                Arguments.of(
                        "RabbitMQ Sender 2 element",
                        "/testData/input/builder/templates/rabbitmq_sender_2.yml",
                        "/testData/output/builder/templates/rabbitmq_sender_2.xml"
                ),
                Arguments.of(
                        "Script element",
                        "/testData/input/builder/templates/script.yml",
                        "/testData/output/builder/templates/script.xml"
                ),
                Arguments.of(
                        "Kafka Service Call element",
                        "/testData/input/builder/templates/service_call_kafka.yml",
                        "/testData/output/builder/templates/service_call_kafka.xml"
                ),
                Arguments.of(
                        "HTTP Service Call element",
                        "/testData/input/builder/templates/service_call_http.yml",
                        "/testData/output/builder/templates/service_call_http.xml"
                ),
                Arguments.of(
                        "SFTP Download element",
                        "/testData/input/builder/templates/sftp_download.yml",
                        "/testData/output/builder/templates/sftp_download.xml"
                ),
                Arguments.of(
                        "SFTP Trigger 2 element",
                        "/testData/input/builder/templates/sftp_trigger_2.yml",
                        "/testData/output/builder/templates/sftp_trigger_2.xml"
                ),
                Arguments.of(
                        "SFTP Upload element",
                        "/testData/input/builder/templates/sftp_upload.yml",
                        "/testData/output/builder/templates/sftp_upload.xml"
                ),
                Arguments.of(
                        "Split-2 element",
                        "/testData/input/builder/templates/split_2.yml",
                        "/testData/output/builder/templates/split_2.xml"
                ),
                Arguments.of(
                        "Split Async-2 element",
                        "/testData/input/builder/templates/split_async_2.yml",
                        "/testData/output/builder/templates/split_async_2.xml"
                ),
                Arguments.of(
                        "Try-Catch-Finally-2 element",
                        "/testData/input/builder/templates/try_catch_finally_2.yml",
                        "/testData/output/builder/templates/try_catch_finally_2.xml"
                ),
                Arguments.of(
                        "XSLT element",
                        "/testData/input/builder/templates/xslt.yml",
                        "/testData/output/builder/templates/xslt.xml"
                )
        );
    }

    private static Stream<Arguments> applyCompositeTriggerTemplateTestData() {
        return Stream.of(
                Arguments.of(
                        "Checkpoint Trigger element",
                        "/testData/input/builder/templates/checkpoint_trigger.yml",
                        "/testData/output/builder/templates/checkpoint_trigger.xml"
                ),
                Arguments.of(
                        "Checkpoint Module element",
                        "/testData/input/builder/templates/checkpoint_module.yml",
                        "/testData/output/builder/templates/checkpoint_module.xml"
                )
        );
    }

    @DisplayName("Test of building xml from template")
    @ParameterizedTest(name = "#{index} => {0}")
    @MethodSource("applyTemplateTestData")
    public void applyTemplateTest(String scenario, String inputPath, String outputPath) throws IOException {
        String expected = TestUtils.getResourceFileContent(outputPath);

        ChainElement testData = chainMapper.toEntity(TestUtils.YAML_MAPPER.readValue(
                        TestUtils.getResourceFileContent(inputPath),
                        ChainImportDTO.class
                ))
                .getElements().stream()
                .filter(element -> element.getParent() == null)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Incorrect test data"));
        String actual = wrap(templateService.applyTemplate(testData));

        assertThat(actual, CompareMatcher.isIdenticalTo(expected).ignoreWhitespace());
    }

    @DisplayName("Test of building composite trigger xml from template")
    @ParameterizedTest(name = "#{index} => {0}")
    @MethodSource("applyCompositeTriggerTemplateTestData")
    public void applyCompositeTriggerTemplateTest(String scenario, String inputPath, String outputPath) throws IOException {
        String expected = TestUtils.getResourceFileContent(outputPath);

        List<ChainElement> testData = chainMapper.toEntity(TestUtils.YAML_MAPPER.readValue(
                        TestUtils.getResourceFileContent(inputPath),
                        ChainImportDTO.class
                ))
                .getElements();

        StringBuilder actualBuilder = new StringBuilder();
        for (ChainElement element : testData) {
            actualBuilder
                    .append("<step>\n")
                    .append(templateService.applyTemplate(element))
                    .append("</step>");
        }
        String actual = wrap(actualBuilder.toString());

        assertThat(actual, CompareMatcher.isIdenticalTo(expected).ignoreWhitespace());
    }

    private String wrap(String xml) {
        return new StringBuilder()
                .append("<route>")
                .append("\n")
                .append(xml)
                .append("</route>")
                .toString();
    }
}

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

package org.qubership.integration.platform.runtime.catalog.cr.sources.builders.xml.beans.builders.element;

import com.ctc.wstx.stax.WstxOutputFactory;
import org.codehaus.stax2.XMLStreamWriter2;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.runtime.catalog.cr.sources.SourceBuilderContext;
import org.qubership.integration.platform.runtime.catalog.cr.sources.builders.xml.beans.builders.element.helpers.MaasClassifierHelper;
import org.qubership.integration.platform.runtime.catalog.model.constant.CamelNames;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Chain;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Snapshot;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;

import java.io.StringWriter;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.qubership.integration.platform.runtime.catalog.model.constant.CamelNames.ASYNC_API_TRIGGER_COMPONENT;
import static org.qubership.integration.platform.runtime.catalog.model.constant.CamelNames.OPERATION_PROTOCOL_TYPE_AMQP;
import static org.qubership.integration.platform.runtime.catalog.model.constant.CamelNames.OPERATION_PROTOCOL_TYPE_KAFKA;
import static org.qubership.integration.platform.runtime.catalog.model.constant.CamelNames.OPERATION_PROTOCOL_TYPE_PROP;
import static org.qubership.integration.platform.runtime.catalog.model.constant.CamelNames.RABBITMQ_SENDER_2_COMPONENT;
import static org.qubership.integration.platform.runtime.catalog.model.constant.CamelNames.RABBITMQ_SENDER_COMPONENT;
import static org.qubership.integration.platform.runtime.catalog.model.constant.CamelNames.RABBITMQ_TRIGGER_2_COMPONENT;
import static org.qubership.integration.platform.runtime.catalog.model.constant.CamelNames.RABBITMQ_TRIGGER_COMPONENT;
import static org.qubership.integration.platform.runtime.catalog.model.constant.CamelNames.SERVICE_CALL_COMPONENT;
import static org.qubership.integration.platform.runtime.catalog.model.constant.CamelOptions.MAAS_CLASSIFIER_NAMESPACE;
import static org.qubership.integration.platform.runtime.catalog.model.constant.CamelOptions.MAAS_CLASSIFIER_TENANT_ENABLED;
import static org.qubership.integration.platform.runtime.catalog.model.constant.CamelOptions.MAAS_CLASSIFIER_TENANT_ID;

@ExtendWith(MockitoExtension.class)
class AmpqBeansBinderTest {

    @Mock
    private MaasClassifierHelper maasClassifierHelper;

    @Mock
    private XMLStreamWriter2 streamWriter;

    private AmpqBeansBinder binder;

    @BeforeEach
    void setUp() {
        binder = new AmpqBeansBinder(maasClassifierHelper);
    }

    // ----- applicableTo -----

    @ParameterizedTest(name = "applicableTo returns true for native RabbitMQ element type: {0}")
    @ValueSource(strings = {
        RABBITMQ_TRIGGER_COMPONENT,
        RABBITMQ_TRIGGER_2_COMPONENT,
        RABBITMQ_SENDER_COMPONENT,
        RABBITMQ_SENDER_2_COMPONENT
    })
    void applicableToReturnsTrueForAllNativeRabbitMqElementTypes(String type) {
        ChainElement element = ChainElement.builder().id("el-id").type(type).build();
        assertTrue(binder.applicableTo(element));
    }

    @ParameterizedTest(name = "applicableTo returns true for {0} component with AMQP protocol")
    @ValueSource(strings = {ASYNC_API_TRIGGER_COMPONENT, SERVICE_CALL_COMPONENT})
    void applicableToReturnsTrueForAsyncApiAndServiceCallWithAmqpProtocol(String type) {
        ChainElement element = ChainElement.builder()
                .id("el-id")
                .type(type)
                .properties(Map.of(OPERATION_PROTOCOL_TYPE_PROP, OPERATION_PROTOCOL_TYPE_AMQP))
                .build();
        assertTrue(binder.applicableTo(element));
    }

    @Test
    void applicableToReturnsFalseForUnrelatedElementType() {
        ChainElement element = ChainElement.builder().id("el-id").type("http-trigger").build();
        assertFalse(binder.applicableTo(element));
    }

    @Test
    void applicableToReturnsFalseForAsyncApiTriggerWithNonAmqpProtocol() {
        ChainElement element = ChainElement.builder()
                .id("el-id")
                .type(ASYNC_API_TRIGGER_COMPONENT)
                .properties(Map.of(OPERATION_PROTOCOL_TYPE_PROP, OPERATION_PROTOCOL_TYPE_KAFKA))
                .build();
        assertFalse(binder.applicableTo(element));
    }

    // ----- build: RabbitMQ trigger -----

    @Test
    void shouldAddMaasClassifierInfoBeanForRabbitMqTriggerWhenClassifierPresent() throws Exception {
        ChainElement element = rabbitMqTriggerElement();

        when(maasClassifierHelper.getMaasClassifierForAmpqElement(element)).thenReturn("test-vhost");

        binder.build(streamWriter, element, SourceBuilderContext.builder().build());

        verify(maasClassifierHelper).addMaasClassifierInfoBean(
                eq(streamWriter),
                eq(element),
                eq(OPERATION_PROTOCOL_TYPE_AMQP),
                eq("test-vhost"),
                isNull(),
                isNull(),
                eq("false")
        );
    }

    @Test
    void shouldNotAddMaasClassifierInfoBeanWhenClassifierBlank() throws Exception {
        ChainElement element = rabbitMqTriggerElement();

        when(maasClassifierHelper.getMaasClassifierForAmpqElement(element)).thenReturn("");

        binder.build(streamWriter, element, SourceBuilderContext.builder().build());

        verify(maasClassifierHelper, never()).addMaasClassifierInfoBean(
                eq(streamWriter),
                eq(element),
                eq(OPERATION_PROTOCOL_TYPE_AMQP),
                eq(""),
                isNull(),
                isNull(),
                eq("false")
        );
    }

    @Test
    void shouldPassNativeTenantFieldsToMaasClassifierInfoBeanForRabbitMqElement() throws Exception {
        ChainElement element = rabbitMqTriggerElementWithTenantProperties("orders-ns", "tenant-1", "true");

        when(maasClassifierHelper.getMaasClassifierForAmpqElement(element)).thenReturn("test-vhost");

        binder.build(streamWriter, element, SourceBuilderContext.builder().build());

        verify(maasClassifierHelper).addMaasClassifierInfoBean(
                eq(streamWriter),
                eq(element),
                eq(OPERATION_PROTOCOL_TYPE_AMQP),
                eq("test-vhost"),
                eq("orders-ns"),
                eq("tenant-1"),
                eq("true")
        );
    }

    // ----- build: AsyncAPI trigger -----

    @Test
    void shouldPassNullTenantFieldsWhenAsyncApiTriggerPropertiesMissing() throws Exception {
        ChainElement element = asyncApiAmqpElement(Map.of());

        when(maasClassifierHelper.getMaasClassifierForServiceCallOrAsyncApiElement(element))
                .thenReturn("async-vhost-classifier");

        binder.build(streamWriter, element, SourceBuilderContext.builder().build());

        verify(maasClassifierHelper).addMaasClassifierInfoBean(
                eq(streamWriter),
                eq(element),
                eq(OPERATION_PROTOCOL_TYPE_AMQP),
                eq("async-vhost-classifier"),
                isNull(),
                isNull(),
                eq("false")
        );
    }

    @Test
    void shouldReadAsyncApiTriggerMaasClassifierTenantFieldsFromProperties() throws Exception {
        Map<String, Object> asyncProperties = Map.of(
                CamelNames.MAAS_CLASSIFIER_NAMESPACE_PROP, "amqp-ns",
                CamelNames.MAAS_CLASSIFIER_TENANT_ID_CAMEL_NAME, "tenant-2",
                CamelNames.MAAS_CLASSIFIER_TENANT_ENABLED_CAMEL_NAME, "true"
        );
        ChainElement element = asyncApiAmqpElement(asyncProperties);

        when(maasClassifierHelper.getMaasClassifierForServiceCallOrAsyncApiElement(element))
                .thenReturn("async-vhost-classifier");

        binder.build(streamWriter, element, SourceBuilderContext.builder().build());

        verify(maasClassifierHelper).addMaasClassifierInfoBean(
                eq(streamWriter),
                eq(element),
                eq(OPERATION_PROTOCOL_TYPE_AMQP),
                eq("async-vhost-classifier"),
                eq("amqp-ns"),
                eq("tenant-2"),
                eq("true")
        );
    }

    // ----- build: ServiceCall -----

    @Test
    void shouldAddMaasClassifierInfoBeanForServiceCallAmqpElementWhenClassifierPresent() throws Exception {
        ChainElement element = serviceCallAmqpElement(Map.of());

        when(maasClassifierHelper.getMaasClassifierForServiceCallOrAsyncApiElement(element))
                .thenReturn("service-call-vhost");

        binder.build(streamWriter, element, SourceBuilderContext.builder().build());

        verify(maasClassifierHelper).addMaasClassifierInfoBean(
                eq(streamWriter),
                eq(element),
                eq(OPERATION_PROTOCOL_TYPE_AMQP),
                eq("service-call-vhost"),
                isNull(),
                isNull(),
                eq("false")
        );
    }

    // ----- build: XML structure -----

    @Test
    void shouldWriteMetricsBeanWithChainAndElementAttributes() throws Exception {
        ChainElement element = rabbitMqTriggerElement();

        when(maasClassifierHelper.getMaasClassifierForAmpqElement(element)).thenReturn("");

        StringWriter result = new StringWriter();
        XMLStreamWriter2 realWriter = (XMLStreamWriter2) new WstxOutputFactory().createXMLStreamWriter(result);
        realWriter.writeStartDocument();
        realWriter.writeStartElement("beans");

        binder.build(realWriter, element, SourceBuilderContext.builder().build());

        realWriter.writeEndElement();
        realWriter.writeEndDocument();
        realWriter.flush();

        String xml = result.toString();
        assertAll(
                () -> assertTrue(xml.contains("name=\"rabbit-element-id\""), "bean name should equal element id"),
                () -> assertTrue(xml.contains("type=\"com.rabbitmq.client.MetricsCollector\""), "bean type"),
                () -> assertTrue(xml.contains("builderClass=\"org.qubership.integration.platform.engine.util.builders.RabbitMQMetricsCollectorBuilder\""), "builderClass"),
                () -> assertTrue(xml.contains("key=\"chainId\" value=\"chain-id\""), "chainId property"),
                () -> assertTrue(xml.contains("key=\"chainName\" value=\"chain-name\""), "chainName property"),
                () -> assertTrue(xml.contains("key=\"elementId\" value=\"rabbit-original-id\""), "elementId property uses originalId"),
                () -> assertTrue(xml.contains("key=\"elementName\" value=\"rabbitmq-trigger\""), "elementName property"),
                () -> assertFalse(xml.contains("key=\"maasClassifier\""), "no maasClassifier property when classifier is blank")
        );
    }

    @Test
    void shouldWriteMaasClassifierPropertyInMetricsBeanWhenClassifierPresent() throws Exception {
        ChainElement element = rabbitMqTriggerElement();

        when(maasClassifierHelper.getMaasClassifierForAmpqElement(element)).thenReturn("test-vhost");

        StringWriter result = new StringWriter();
        XMLStreamWriter2 realWriter = (XMLStreamWriter2) new WstxOutputFactory().createXMLStreamWriter(result);
        realWriter.writeStartDocument();
        realWriter.writeStartElement("beans");

        binder.build(realWriter, element, SourceBuilderContext.builder().build());

        realWriter.writeEndElement();
        realWriter.writeEndDocument();
        realWriter.flush();

        assertTrue(
                result.toString().contains("key=\"maasClassifier\" value=\"test-vhost\""),
                result.toString()
        );
    }

    // ----- helpers -----

    private static ChainElement rabbitMqTriggerElement() {
        Chain chain = Chain.builder().id("chain-id").name("chain-name").build();
        Snapshot snapshot = Snapshot.builder().chain(chain).build();
        return ChainElement.builder()
                .id("rabbit-element-id")
                .originalId("rabbit-original-id")
                .name("rabbitmq-trigger")
                .type(RABBITMQ_TRIGGER_2_COMPONENT)
                .snapshot(snapshot)
                .build();
    }

    private static ChainElement rabbitMqTriggerElementWithTenantProperties(
            String namespace, String tenantId, String tenantEnabled) {
        Chain chain = Chain.builder().id("chain-id").name("chain-name").build();
        Snapshot snapshot = Snapshot.builder().chain(chain).build();
        Map<String, Object> properties = new HashMap<>();
        properties.put(MAAS_CLASSIFIER_NAMESPACE, namespace);
        properties.put(MAAS_CLASSIFIER_TENANT_ID, tenantId);
        properties.put(MAAS_CLASSIFIER_TENANT_ENABLED, tenantEnabled);
        return ChainElement.builder()
                .id("rabbit-element-id")
                .originalId("rabbit-original-id")
                .name("rabbitmq-trigger")
                .type(RABBITMQ_TRIGGER_2_COMPONENT)
                .snapshot(snapshot)
                .properties(properties)
                .build();
    }

    private static ChainElement asyncApiAmqpElement(Map<String, Object> asyncProperties) {
        Chain chain = Chain.builder().id("chain-id").name("chain-name").build();
        Snapshot snapshot = Snapshot.builder().chain(chain).build();
        Map<String, Object> properties = new HashMap<>();
        properties.put(OPERATION_PROTOCOL_TYPE_PROP, OPERATION_PROTOCOL_TYPE_AMQP);
        if (!asyncProperties.isEmpty()) {
            properties.put(CamelNames.OPERATION_ASYNC_PROPERTIES, asyncProperties);
        }
        return ChainElement.builder()
                .id("async-amqp-id")
                .originalId("async-amqp-original-id")
                .name("async-api-trigger")
                .type(ASYNC_API_TRIGGER_COMPONENT)
                .snapshot(snapshot)
                .properties(properties)
                .build();
    }

    private static ChainElement serviceCallAmqpElement(Map<String, Object> asyncProperties) {
        Chain chain = Chain.builder().id("chain-id").name("chain-name").build();
        Snapshot snapshot = Snapshot.builder().chain(chain).build();
        Map<String, Object> properties = new HashMap<>();
        properties.put(OPERATION_PROTOCOL_TYPE_PROP, OPERATION_PROTOCOL_TYPE_AMQP);
        if (!asyncProperties.isEmpty()) {
            properties.put(CamelNames.OPERATION_ASYNC_PROPERTIES, asyncProperties);
        }
        return ChainElement.builder()
                .id("service-call-amqp-id")
                .originalId("service-call-amqp-original-id")
                .name("service-call-amqp")
                .type(SERVICE_CALL_COMPONENT)
                .snapshot(snapshot)
                .properties(properties)
                .build();
    }
}

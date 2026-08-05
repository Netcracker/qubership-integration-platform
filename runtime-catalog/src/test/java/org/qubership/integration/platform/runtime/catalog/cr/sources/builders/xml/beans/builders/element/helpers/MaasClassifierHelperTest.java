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

package org.qubership.integration.platform.runtime.catalog.cr.sources.builders.xml.beans.builders.element.helpers;

import com.ctc.wstx.stax.WstxOutputFactory;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.codehaus.stax2.XMLStreamWriter2;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.Environment;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.IntegrationSystem;
import org.qubership.integration.platform.runtime.catalog.service.EnvironmentService;
import org.qubership.integration.platform.runtime.catalog.service.SystemService;

import java.io.StringWriter;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;
import static org.qubership.integration.platform.runtime.catalog.model.constant.CamelNames.MAAS_CLASSIFIER_NAME_PROP;
import static org.qubership.integration.platform.runtime.catalog.model.constant.CamelNames.OPERATION_ASYNC_PROPERTIES;
import static org.qubership.integration.platform.runtime.catalog.model.constant.CamelOptions.CONNECTION_SOURCE_TYPE_PROP;
import static org.qubership.integration.platform.runtime.catalog.model.constant.CamelOptions.DEFAULT_VHOST_CLASSIFIER_NAME;
import static org.qubership.integration.platform.runtime.catalog.model.constant.CamelOptions.MAAS_TOPICS_CLASSIFIER_NAME_PROP;
import static org.qubership.integration.platform.runtime.catalog.model.constant.CamelOptions.MAAS_VHOST_CLASSIFIER_NAME_PROP;
import static org.qubership.integration.platform.runtime.catalog.model.constant.CamelOptions.SYSTEM_ID;

@ExtendWith(MockitoExtension.class)
class MaasClassifierHelperTest {

    @Mock
    private SystemService systemService;

    @Mock
    private EnvironmentService environmentService;

    @Mock
    private ChainElement element;

    @Mock
    private IntegrationSystem system;

    @Mock
    private Environment environment;

    // ----- getMaasClassifierForKafkaElement -----

    @Test
    void shouldResolveKafkaClassifierWhenConnectionSourceTypeIsLowerCaseMaas() {
        MaasClassifierHelper helper = new MaasClassifierHelper(systemService, environmentService);

        when(element.getPropertyAsString(CONNECTION_SOURCE_TYPE_PROP)).thenReturn("maas");
        when(element.getPropertyAsString(MAAS_TOPICS_CLASSIFIER_NAME_PROP)).thenReturn("orders-topic");

        assertEquals("orders-topic", helper.getMaasClassifierForKafkaElement(element));
    }

    @Test
    void shouldResolveKafkaClassifierWhenConnectionSourceTypeIsMaasByClassifier() {
        MaasClassifierHelper helper = new MaasClassifierHelper(systemService, environmentService);

        when(element.getPropertyAsString(CONNECTION_SOURCE_TYPE_PROP)).thenReturn("maas_by_classifier");
        when(element.getPropertyAsString(MAAS_TOPICS_CLASSIFIER_NAME_PROP)).thenReturn("orders-topic");

        assertEquals("orders-topic", helper.getMaasClassifierForKafkaElement(element));
    }

    @Test
    void shouldNotResolveKafkaClassifierWhenConnectionSourceTypeIsManual() {
        MaasClassifierHelper helper = new MaasClassifierHelper(systemService, environmentService);

        when(element.getPropertyAsString(CONNECTION_SOURCE_TYPE_PROP)).thenReturn("manual");

        assertEquals("", helper.getMaasClassifierForKafkaElement(element));
    }

    @Test
    void shouldReturnEmptyStringForKafkaClassifierWhenClassifierNameIsNull() {
        MaasClassifierHelper helper = new MaasClassifierHelper(systemService, environmentService);

        when(element.getPropertyAsString(CONNECTION_SOURCE_TYPE_PROP)).thenReturn("maas");
        when(element.getPropertyAsString(MAAS_TOPICS_CLASSIFIER_NAME_PROP)).thenReturn(null);

        assertEquals("", helper.getMaasClassifierForKafkaElement(element));
    }

    // ----- getMaasClassifierForAmpqElement -----

    @Test
    void shouldResolveAmpqClassifierWhenConnectionSourceTypeIsLowerCaseMaas() {
        MaasClassifierHelper helper = new MaasClassifierHelper(systemService, environmentService);

        when(element.getPropertyAsString(CONNECTION_SOURCE_TYPE_PROP)).thenReturn("maas");
        when(element.getPropertyAsString(MAAS_VHOST_CLASSIFIER_NAME_PROP)).thenReturn("custom-vhost");

        assertEquals("custom-vhost", helper.getMaasClassifierForAmpqElement(element));
    }

    @Test
    void shouldUseDefaultVhostClassifierWhenAmpqClassifierNameMissing() {
        MaasClassifierHelper helper = new MaasClassifierHelper(systemService, environmentService);

        when(element.getPropertyAsString(CONNECTION_SOURCE_TYPE_PROP)).thenReturn("MAAS");
        when(element.getPropertyAsString(MAAS_VHOST_CLASSIFIER_NAME_PROP)).thenReturn(null);

        assertEquals(DEFAULT_VHOST_CLASSIFIER_NAME, helper.getMaasClassifierForAmpqElement(element));
    }

    @Test
    void shouldNotResolveAmpqClassifierWhenConnectionSourceTypeIsManual() {
        MaasClassifierHelper helper = new MaasClassifierHelper(systemService, environmentService);

        when(element.getPropertyAsString(CONNECTION_SOURCE_TYPE_PROP)).thenReturn("manual");

        assertEquals("", helper.getMaasClassifierForAmpqElement(element));
    }

    // ----- getMaasClassifierForServiceCallOrAsyncApiElement -----

    @Test
    void shouldReadClassifierFromIntegrationOperationAsyncProperties() {
        MaasClassifierHelper helper = new MaasClassifierHelper(systemService, environmentService);

        Map<String, Object> properties = new HashMap<>();
        properties.put(
                OPERATION_ASYNC_PROPERTIES,
                Map.of(MAAS_CLASSIFIER_NAME_PROP, "async-classifier")
        );
        when(element.getProperties()).thenReturn(properties);

        assertEquals("async-classifier", helper.getMaasClassifierForServiceCallOrAsyncApiElement(element));
    }

    @Test
    void shouldFallBackToSystemEnvironmentServiceWhenClassifierNotInAsyncProperties() {
        MaasClassifierHelper helper = new MaasClassifierHelper(systemService, environmentService);

        when(element.getProperties()).thenReturn(new HashMap<>());
        when(element.getPropertyAsString(SYSTEM_ID)).thenReturn("system-id");
        when(system.getId()).thenReturn("system-id");
        when(system.getActiveEnvironmentId()).thenReturn("env-id");
        when(systemService.getByIdOrNull("system-id")).thenReturn(system);

        ObjectNode envProperties = JsonNodeFactory.instance.objectNode();
        envProperties.put(MAAS_CLASSIFIER_NAME_PROP, "env-classifier");
        when(environment.getProperties()).thenReturn(envProperties);
        when(environmentService.getByIdForSystem("system-id", "env-id")).thenReturn(environment);

        assertEquals("env-classifier", helper.getMaasClassifierForServiceCallOrAsyncApiElement(element));
    }

    @Test
    void shouldReturnEmptyStringWhenNoAsyncClassifierAndNoSystemId() {
        MaasClassifierHelper helper = new MaasClassifierHelper(systemService, environmentService);

        when(element.getProperties()).thenReturn(new HashMap<>());
        when(element.getPropertyAsString(SYSTEM_ID)).thenReturn(null);

        assertEquals("", helper.getMaasClassifierForServiceCallOrAsyncApiElement(element));
    }

    // ----- addMaasClassifierInfoBean -----

    @Test
    void shouldWriteAllPropertiesInMaasClassifierInfoBean() throws Exception {
        MaasClassifierHelper helper = new MaasClassifierHelper(systemService, environmentService);

        when(element.getId()).thenReturn("el-id");
        when(element.getOriginalId()).thenReturn("original-el-id");

        StringWriter result = new StringWriter();
        XMLStreamWriter2 streamWriter = (XMLStreamWriter2) new WstxOutputFactory().createXMLStreamWriter(result);
        streamWriter.writeStartDocument();

        helper.addMaasClassifierInfoBean(
                streamWriter, element, "kafka", "orders-classifier", "my-namespace", "tenant-42", "true"
        );

        streamWriter.writeEndDocument();
        streamWriter.flush();

        String xml = result.toString();
        assertAll(
                () -> assertTrue(xml.contains("name=\"MaasClassifierInfo-el-id\""), "bean name includes element id"),
                () -> assertTrue(xml.contains("type=\"org.qubership.integration.platform.engine.metadata.MaasClassifierInfo\""), "bean type"),
                () -> assertTrue(xml.contains("key=\"elementId\" value=\"original-el-id\""), "elementId uses originalId"),
                () -> assertTrue(xml.contains("key=\"protocol\" value=\"kafka\""), "protocol"),
                () -> assertTrue(xml.contains("key=\"classifier\" value=\"orders-classifier\""), "classifier"),
                () -> assertTrue(xml.contains("key=\"namespace\" value=\"my-namespace\""), "namespace"),
                () -> assertTrue(xml.contains("key=\"tenantId\" value=\"tenant-42\""), "tenantId"),
                () -> assertTrue(xml.contains("key=\"tenantEnabled\" value=\"true\""), "tenantEnabled")
        );
    }

    @Test
    void shouldWriteEmptyStringForNullPropertiesInMaasClassifierInfoBean() throws Exception {
        MaasClassifierHelper helper = new MaasClassifierHelper(systemService, environmentService);

        when(element.getId()).thenReturn("element-id");
        when(element.getOriginalId()).thenReturn("original-element-id");

        StringWriter result = new StringWriter();
        XMLStreamWriter2 streamWriter = (XMLStreamWriter2) new WstxOutputFactory().createXMLStreamWriter(result);
        streamWriter.writeStartDocument();

        helper.addMaasClassifierInfoBean(
                streamWriter,
                element,
                "kafka",
                "orders-topic",
                null,
                null,
                null
        );

        streamWriter.writeEndDocument();
        streamWriter.flush();

        String xml = result.toString();
        assertAll(
                () -> assertTrue(xml.contains("key=\"namespace\" value=\"\""), "null namespace becomes empty string"),
                () -> assertTrue(xml.contains("key=\"tenantId\" value=\"\""), "null tenantId becomes empty string"),
                () -> assertTrue(xml.contains("key=\"tenantEnabled\" value=\"\""), "null tenantEnabled becomes empty string")
        );
    }
}

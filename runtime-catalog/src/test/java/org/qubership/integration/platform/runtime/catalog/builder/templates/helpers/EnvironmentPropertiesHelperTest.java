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

package org.qubership.integration.platform.runtime.catalog.builder.templates.helpers;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.apache.commons.lang3.StringUtils;
import org.json.JSONException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.SnapshotCreationException;
import org.qubership.integration.platform.runtime.catalog.model.system.ServiceEnvironment;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.testutils.TestUtils;
import org.skyscreamer.jsonassert.JSONAssert;
import org.skyscreamer.jsonassert.JSONCompareMode;

import java.io.IOException;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.IsEqual.equalTo;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class EnvironmentPropertiesHelperTest {

    private final EnvironmentPropertiesHelper helper = new EnvironmentPropertiesHelper();


    @DisplayName("Extracting environment properties")
    @Test
    public void environmentPropertiesJsonTest() throws IOException, JSONException {
        String expected = """
                   {
                      "namespace": "a05",
                      "dbSystemName": "d711bef5-14fd-4295-a665-92b99936649e",
                      "microserviceName": "cloud-integration-platform-engine"
                   }
                """;

        String environmentJson = """
                   {
                      "id": "d711bef5-14fd-4295-a665-92b99936649e",
                      "systemId": "d711bef5-14fd-4295-a665-92b99936649e",
                      "properties": {
                         "namespace": "a05",
                         "dbSystemName": "d711bef5-14fd-4295-a665-92b99936649e",
                         "microserviceName": "cloud-integration-platform-engine"
                      }
                   }
                """;
        ChainElement testData = new ChainElement();
        testData.setEnvironment(TestUtils.OBJECT_MAPPER.readValue(environmentJson, ServiceEnvironment.class));

        String actual = String.valueOf(helper.environmentPropertiesJson(testData));

        JSONAssert.assertEquals(expected, actual, JSONCompareMode.NON_EXTENSIBLE);
    }

    @DisplayName("Extracting empty environment properties")
    @Test
    public void emptyEnvironmentPropertiesJsonTest() throws JsonProcessingException {
        String environmentJson = """
                   {
                      "id": "d711bef5-14fd-4295-a665-92b99936649e",
                      "systemId": "d711bef5-14fd-4295-a665-92b99936649e"
                   }
                """;
        ChainElement testData = new ChainElement();
        testData.setEnvironment(TestUtils.OBJECT_MAPPER.readValue(environmentJson, ServiceEnvironment.class));

        String actual = String.valueOf(helper.environmentPropertiesJson(testData));

        assertThat(actual, equalTo(StringUtils.EMPTY));
    }

    @DisplayName("Extracting properties from null environment")
    @Test
    public void nullEnvironmentPropertiesJsonTest() {
        ChainElement testData = new ChainElement();

        assertThrows(SnapshotCreationException.class, () -> helper.environmentPropertiesJson(testData));
    }

    @DisplayName("Extracting async environment properties")
    @Test
    public void asyncPropertiesJsonTest() throws IOException, JSONException {
        String expected = """
                   {
                      "groupId": "{ENV}-qip-kafka-consumer-group",
                      "saslMechanism": "{KAFKA_SASL_MECHANISM}",
                      "maxPollRecords": "5",
                      "saslJaasConfig": "{KAFKA_SASL_JAAS_CONFIG}",
                      "securityProtocol": "{KAFKA_SECURITY_PROTOCOL}",
                      "maxPollIntervalMs": "600000"
                   }
                """;

        String environmentJson = """
                   {
                      "id": "9c8c1b26-63b6-4f6d-91f9-371d743965f0",
                      "systemId": "8b3663ff-7dc5-45d1-afd4-0de909ffdc65",
                      "properties": {
                         "groupId": "{ENV}-qip-kafka-consumer-group",
                         "saslMechanism": "{KAFKA_SASL_MECHANISM}",
                         "maxPollRecords": "5",
                         "saslJaasConfig": "{KAFKA_SASL_JAAS_CONFIG}",
                         "securityProtocol": "{KAFKA_SECURITY_PROTOCOL}",
                         "maxPollIntervalMs": "600000"
                      }
                   }
                """;
        ChainElement testData = new ChainElement();
        testData.setEnvironment(TestUtils.OBJECT_MAPPER.readValue(environmentJson, ServiceEnvironment.class));

        String actual = String.valueOf(helper.asyncPropertiesJson(testData));

        JSONAssert.assertEquals(expected, actual, JSONCompareMode.NON_EXTENSIBLE);
    }

    @DisplayName("Extracting empty async environment properties")
    @Test
    public void emptyAsyncPropertiesJsonTest() throws JsonProcessingException {
        String environmentJson = """
                   {
                      "id": "9c8c1b26-63b6-4f6d-91f9-371d743965f0",
                      "systemId": "8b3663ff-7dc5-45d1-afd4-0de909ffdc65"
                   }
                """;
        ChainElement testData = new ChainElement();
        testData.setEnvironment(TestUtils.OBJECT_MAPPER.readValue(environmentJson, ServiceEnvironment.class));

        String actual = String.valueOf(helper.asyncPropertiesJson(testData));

        assertThat(actual, equalTo(StringUtils.EMPTY));
    }

    @DisplayName("Extracting async properties from null environment")
    @Test
    public void nullAsyncEnvironmentPropertiesJsonTest() {
        ChainElement testData = new ChainElement();

        assertThrows(SnapshotCreationException.class, () -> helper.asyncPropertiesJson(testData));
    }
}

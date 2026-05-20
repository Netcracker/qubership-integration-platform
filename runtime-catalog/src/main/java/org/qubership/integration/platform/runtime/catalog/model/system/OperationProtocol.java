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

package org.qubership.integration.platform.runtime.catalog.model.system;

import lombok.Getter;

import java.util.ArrayList;
import java.util.List;

@Getter
public enum OperationProtocol {
    HTTP("http", "swagger"),
    AMQP("amqp", "asyncapi"),
    KAFKA("kafka", "asyncapi"),
    SOAP("http", "soap"),
    GRAPHQL("graphql", "graphqlschema"),
    METAMODEL("metamodel", "metamodel"),
    GRPC("grpc", "protobuf");

    public final String value;
    public final String type;

    OperationProtocol(String value, String type) {
        this.value = value;
        this.type = type;
    }

    public static OperationProtocol fromValue(String text) {
        for (OperationProtocol b : OperationProtocol.values()) {
            if (String.valueOf(b.value).equals(text)) {
                return b;
            }
        }
        return null;
    }

    public static List<OperationProtocol> receiveProtocolsFromType(String type) {
        List<OperationProtocol> operationProtocols = new ArrayList<>();
        for (OperationProtocol protocol : OperationProtocol.values()) {
            if (String.valueOf(protocol.type).equals(type)) {
                operationProtocols.add(protocol);
            }
        }
        return operationProtocols;
    }

    public static OperationProtocol fromType(String type) {
        for (OperationProtocol b : OperationProtocol.values()) {
            if (String.valueOf(b.type).equals(type)) {
                return b;
            }
        }
        return null;
    }

    public static boolean isAsyncProtocol(OperationProtocol protocol) {
        return protocol == AMQP || protocol == KAFKA;
    }
}

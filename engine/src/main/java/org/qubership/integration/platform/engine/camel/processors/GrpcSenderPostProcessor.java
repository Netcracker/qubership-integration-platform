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

package org.qubership.integration.platform.engine.camel.processors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.util.JsonFormat;
import org.qubership.integration.platform.engine.model.constants.CamelConstants;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.util.List;


@Component
public class GrpcSenderPostProcessor implements Processor {

    private final JsonFormat.Printer grpcPrinter;
    private final ObjectMapper objectMapper;

    @Autowired
    public GrpcSenderPostProcessor(JsonFormat.Printer grpcPrinter, @Qualifier("jsonMapper") ObjectMapper objectMapper) {
        this.grpcPrinter = grpcPrinter;
        this.objectMapper = objectMapper;
    }

    @Override
    public void process(Exchange exchange) throws Exception {
        exchange.getMessage().setBody(extractBodyAsJsonString(exchange));
        exchange.getMessage().setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);

        exchange.removeProperty(CamelConstants.Properties.GRPC_SERVICE_NAME);
        exchange.removeProperty(CamelConstants.Properties.GRPC_METHOD_NAME);
    }

    private String extractBodyAsJsonString(Exchange exchange)
            throws InvalidProtocolBufferException, JsonProcessingException {
        Object body = exchange.getMessage().getBody();
        if (body instanceof List<?> list) {
            ArrayNode responses = objectMapper.createArrayNode();
            for (Object response : list) {
                String text = extractMessageAsJsonString(exchange, response);
                responses.add(objectMapper.readTree(text));
            }
            return objectMapper.writeValueAsString(responses);
        } else {
            return extractMessageAsJsonString(exchange, body);
        }
    }

    private String extractMessageAsJsonString(Exchange exchange, Object obj) throws InvalidProtocolBufferException {
        Message message = exchange.getContext().getTypeConverter().convertTo(Message.class, obj);
        return grpcPrinter.print(message);
    }
}

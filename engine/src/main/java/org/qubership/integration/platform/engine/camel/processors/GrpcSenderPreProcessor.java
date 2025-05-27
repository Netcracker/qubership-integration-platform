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

import com.google.protobuf.Message;
import com.google.protobuf.util.JsonFormat;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.commons.lang3.StringUtils;
import org.qubership.integration.platform.engine.util.GrpcProcessorUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class GrpcSenderPreProcessor implements Processor {

    private final JsonFormat.Parser grpcParser;

    @Autowired
    public GrpcSenderPreProcessor(JsonFormat.Parser grpcParser) {
        this.grpcParser = grpcParser;
    }

    @Override
    public void process(Exchange exchange) throws Exception {
        org.apache.camel.Message message = exchange.getMessage();
        String body = message.getBody(String.class);

        Class<?> requestC = GrpcProcessorUtils.getRequestClass(exchange);
        Message.Builder builder = (Message.Builder) requestC.getMethod("newBuilder")
            .invoke(requestC);
        if (StringUtils.isNotEmpty(body)) {
            grpcParser.merge(body, builder);
        }

        message.setBody(builder.build(), requestC);
    }
}

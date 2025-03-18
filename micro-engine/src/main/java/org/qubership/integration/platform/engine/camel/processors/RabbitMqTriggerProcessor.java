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

import com.rabbitmq.client.Channel;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.component.springrabbit.SpringRabbitMQConstants;
import org.apache.commons.lang3.StringUtils;
import org.qubership.integration.platform.engine.camel.JsonMessageValidator;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.Properties;
import org.qubership.integration.platform.engine.service.debugger.util.MessageHelper;
import org.qubership.integration.platform.engine.util.ExchangeUtils;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class RabbitMqTriggerProcessor implements Processor {
    private final JsonMessageValidator validator;

    @Autowired
    public RabbitMqTriggerProcessor(JsonMessageValidator jsonMessageValidator) {
        this.validator = jsonMessageValidator;
    }

    @Override
    public void process(Exchange exchange) throws Exception {
        immediateAck(exchange);
        validate(exchange);
    }

    private void immediateAck(Exchange exchange) throws IOException {
        if (StringUtils.isNotEmpty(exchange.getProperty(Properties.ACKNOWLEDGE_MODE_PROP, String.class))) {
            AcknowledgeMode ackMode = exchange.getProperty(Properties.ACKNOWLEDGE_MODE_PROP,
                AcknowledgeMode.AUTO, AcknowledgeMode.class);
            if (ackMode == AcknowledgeMode.MANUAL) {
                Channel channel = exchange.getProperty(SpringRabbitMQConstants.CHANNEL,
                    Channel.class);
                long deliveryTag = exchange.getMessage()
                    .getHeader(SpringRabbitMQConstants.DELIVERY_TAG, long.class);
                channel.basicAck(deliveryTag, false);
            }
        }
    }

    private void validate(Exchange exchange) throws IOException {
        String asyncValidationSchema = (String) exchange.getProperty(Properties.ASYNC_VALIDATION_SCHEMA);
        ExchangeUtils.setContentTypeIfMissing(exchange);
        if (asyncValidationSchema != null && !asyncValidationSchema.isEmpty()) {
            String inputJsonMessage = MessageHelper.extractBody(exchange);
            validator.validate(inputJsonMessage, asyncValidationSchema);
        }
    }
}

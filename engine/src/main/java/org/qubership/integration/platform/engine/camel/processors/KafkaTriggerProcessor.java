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

import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.qubership.integration.platform.engine.camel.JsonMessageValidator;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.Properties;
import org.qubership.integration.platform.engine.service.debugger.util.MessageHelper;
import org.qubership.integration.platform.engine.util.ExchangeUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;


@Component
public class KafkaTriggerProcessor implements Processor {

    private final JsonMessageValidator validator;

    @Autowired
    public KafkaTriggerProcessor(JsonMessageValidator jsonMessageValidator) {
        this.validator = jsonMessageValidator;
    }

    @Override
    public void process(Exchange exchange) throws Exception {
        String asyncValidationSchema = (String) exchange.getProperty(Properties.ASYNC_VALIDATION_SCHEMA);
        ExchangeUtils.setContentTypeIfMissing(exchange);
        if (asyncValidationSchema != null && !asyncValidationSchema.isEmpty()) {
            String inputJsonMessage = MessageHelper.extractBody(exchange);
            validator.validate(inputJsonMessage, asyncValidationSchema);
        }
    }
}

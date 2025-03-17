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
import org.apache.http.HttpHeaders;
import org.qubership.integration.platform.engine.camel.components.context.propagation.RabbitContextPropagationWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class RabbitMqSenderProcessor implements Processor {
    private final Optional<RabbitContextPropagationWrapper> contextPropagationWrapper;

    @Autowired
    public RabbitMqSenderProcessor(
        Optional<RabbitContextPropagationWrapper> contextPropagationWrapper) {
        this.contextPropagationWrapper = contextPropagationWrapper;
    }

    @Override
    public void process(Exchange exchange) throws Exception {
        // Dump context to rabbitmq headers. The context is already propagated,
        // but some headers need to be adapted (e.g. 'x-version' -> 'version')
        contextPropagationWrapper.ifPresent(bean ->
            bean.dumpContext((k, v) -> exchange.getMessage().getHeaders().put(k, v)));

        exchange.getMessage().removeHeader(HttpHeaders.AUTHORIZATION);
    }
}

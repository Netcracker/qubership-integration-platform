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

package org.qubership.integration.platform.engine.service.debugger.tracing;

import static org.qubership.integration.platform.engine.model.constants.CamelConstants.Properties.TRACING_CUSTOM_TAGS;

import java.util.HashMap;
import java.util.Map;
import org.apache.camel.Exchange;
import org.apache.camel.api.management.ManagedResource;
import org.apache.camel.observation.MicrometerObservationTracer;
import org.apache.camel.tracing.InjectAdapter;
import org.apache.camel.tracing.SpanAdapter;
import org.apache.camel.tracing.SpanDecorator;
import org.apache.camel.tracing.SpanKind;
import org.jetbrains.annotations.NotNull;

@ManagedResource(description = "MicrometerObservationTracer")
public class MicrometerObservationTaggedTracer extends MicrometerObservationTracer {

    @Override
    protected SpanAdapter startExchangeBeginSpan(Exchange exchange, SpanDecorator sd,
        String operationName, SpanKind kind, SpanAdapter parent
    ) {
        return insertCustomTagsToSpan(exchange, super.startExchangeBeginSpan(exchange, sd, operationName, kind, parent));
    }

    @Override
    protected SpanAdapter startSendingEventSpan(String operationName, SpanKind kind,
        SpanAdapter parent, Exchange exchange, InjectAdapter injectAdapter
    ) {
        return insertCustomTagsToSpan(exchange, super.startSendingEventSpan(operationName, kind, parent, exchange, injectAdapter));
    }

    @NotNull
    public static SpanAdapter insertCustomTagsToSpan(Exchange exchange, SpanAdapter spanAdapter) {
        Map<String, String> tags = (Map<String, String>) exchange
            .getProperties()
            .getOrDefault(TRACING_CUSTOM_TAGS, new HashMap<>());
        tags.forEach(spanAdapter::setTag);
        return spanAdapter;
    }
}

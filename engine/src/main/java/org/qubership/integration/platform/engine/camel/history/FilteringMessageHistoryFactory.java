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

package org.qubership.integration.platform.engine.camel.history;

import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.MessageHistory;
import org.apache.camel.NamedNode;
import org.apache.camel.spi.MessageHistoryFactory;

import java.util.function.Predicate;

public class FilteringMessageHistoryFactory implements MessageHistoryFactory {
    public record FilteringEntity(
            String routeId,
            NamedNode node,
            Exchange exchange
    ) {}

    private final Predicate<FilteringEntity> filter;
    private final MessageHistoryFactory factory;

    public FilteringMessageHistoryFactory(
            Predicate<FilteringEntity> filter,
            MessageHistoryFactory factory
    ) {
        this.filter = filter;
        this.factory = factory;
    }

    @Override
    public MessageHistory newMessageHistory(String routeId, NamedNode node, Exchange exchange) {
        return filter.test(new FilteringEntity(routeId, node, exchange))
                ? factory.newMessageHistory(routeId, node, exchange)
                : null;
    }

    @Override
    public MessageHistory newMessageHistory(String routeId, NamedNode node, long timestamp, Exchange exchange) {
        return filter.test(new FilteringEntity(routeId, node, exchange))
                ? factory.newMessageHistory(routeId, node, timestamp, exchange)
                : null;
    }

    @Override
    public boolean isCopyMessage() {
        return factory.isCopyMessage();
    }

    @Override
    public void setCopyMessage(boolean copyMessage) {
        factory.setCopyMessage(copyMessage);
    }

    @Override
    public String getNodePattern() {
        return factory.getNodePattern();
    }

    @Override
    public void setNodePattern(String nodePattern) {
        factory.setNodePattern(nodePattern);
    }

    @Override
    public CamelContext getCamelContext() {
        return factory.getCamelContext();
    }

    @Override
    public void setCamelContext(CamelContext camelContext) {
        factory.setCamelContext(camelContext);
    }

    @Override
    public void start() {
        factory.start();
    }

    @Override
    public void stop() {
        factory.stop();
    }
}

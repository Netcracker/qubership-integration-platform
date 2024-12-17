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

package org.qubership.integration.platform.engine.camel.components.directvm;

import org.apache.camel.Endpoint;
import org.apache.camel.spi.HeaderFilterStrategy;
import org.apache.camel.spi.Metadata;
import org.apache.camel.spi.annotations.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.apache.camel.support.DefaultComponent;

import static java.util.Objects.isNull;

/**
 * Based on deleted DirectVmComponent in camel 4.x
 */
@Component("cip-chain")
public class ChainComponent extends DefaultComponent {
    // <key, consumers>
    private static final ConcurrentMap<String, List<ChainConsumer>> consumers = new ConcurrentHashMap<>();

    @Metadata(label = "producer", defaultValue = "true")
    private boolean block = true;
    @Metadata(label = "producer", defaultValue = "30000")
    private long timeout = 30000L;
    @Metadata(label = "advanced")
    private HeaderFilterStrategy headerFilterStrategy;
    @Metadata(label = "advanced", defaultValue = "true")
    private boolean propagateProperties = true;


    public ChainComponent() {
        super();
    }

    public ChainConsumer getConsumer(ChainEndpoint endpoint) {
        String key = getConsumerKey(endpoint.getEndpointUri());
        List<ChainConsumer> consumers = getConsumers().get(key);
        return isNull(consumers) || consumers.isEmpty() ? null : consumers.getLast();
    }

    public void addConsumer(ChainEndpoint endpoint, ChainConsumer consumer) {
        String key = getConsumerKey(endpoint.getEndpointUri());
        getConsumers().merge(key, Collections.singletonList(consumer), (oldValue, value) -> {
            List<ChainConsumer> result = new ArrayList<>(oldValue);
            result.addAll(value);
            return result;
        });
    }

    public void removeConsumer(ChainEndpoint endpoint, ChainConsumer consumer) {
        String key = getConsumerKey(endpoint.getEndpointUri());
        getConsumers().compute(key, (k, oldValue) -> {
            if (isNull(oldValue)) {
                return null;
            }
            List<ChainConsumer> result = new ArrayList<>(oldValue);
            result.remove(consumer);
            return result.isEmpty() ? null : result;
        });
    }

    @Override
    protected Endpoint createEndpoint(String uri, String remaining, Map<String, Object> parameters) throws Exception {
        ChainEndpoint answer = new ChainEndpoint(uri, this);
        answer.setBlock(isBlock());
        answer.setTimeout(getTimeout());
        answer.setPropagateProperties(isPropagateProperties());
        setProperties(answer, parameters);
        return answer;
    }

    private ConcurrentMap<String, List<ChainConsumer>> getConsumers() {
        return consumers;
    }

    private static String getConsumerKey(String uri) {
        if (uri.contains("?")) {
            // strip parameters
            uri = uri.substring(0, uri.indexOf('?'));
        }
        return uri;
    }

    public boolean isBlock() {
        return block;
    }

    /**
     * If sending a message to a direct endpoint which has no active consumer, then we can tell the producer to block
     * and wait for the consumer to become active.
     */
    public void setBlock(boolean block) {
        this.block = block;
    }

    public long getTimeout() {
        return timeout;
    }

    /**
     * The timeout value to use if block is enabled.
     */
    public void setTimeout(long timeout) {
        this.timeout = timeout;
    }

    public HeaderFilterStrategy getHeaderFilterStrategy() {
        return headerFilterStrategy;
    }

    /**
     * Sets a {@link HeaderFilterStrategy} that will only be applied on producer endpoints (on both directions: request
     * and response).
     * <p>
     * Default value: none.
     * </p>
     */
    public void setHeaderFilterStrategy(HeaderFilterStrategy headerFilterStrategy) {
        this.headerFilterStrategy = headerFilterStrategy;
    }

    public boolean isPropagateProperties() {
        return propagateProperties;
    }

    /**
     * Whether to propagate or not properties from the producer side to the consumer side, and vice versa.
     * <p>
     * Default value: true.
     * </p>
     */
    public void setPropagateProperties(boolean propagateProperties) {
        this.propagateProperties = propagateProperties;
    }
}

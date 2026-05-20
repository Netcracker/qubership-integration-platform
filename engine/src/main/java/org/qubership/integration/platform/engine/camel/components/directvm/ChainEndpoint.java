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

import org.apache.camel.*;
import org.apache.camel.spi.*;
import org.apache.camel.support.DefaultEndpoint;

@UriEndpoint(
        firstVersion = "2.10.0",
        scheme = "cip-chain",
        title = "Chain",
        syntax = "cip-chain:name",
        category = { Category.CORE }
)
public class ChainEndpoint extends DefaultEndpoint implements AsyncEndpoint {

    @UriPath(description = "Name of direct-vm endpoint")
    @Metadata(required = true)
    private String name;

    @UriParam(label = "producer", defaultValue = "true")
    private boolean block = true;
    @UriParam(label = "producer", defaultValue = "30000", javaType = "java.time.Duration")
    private long timeout = 30000L;
    @UriParam(label = "producer")
    private boolean failIfNoConsumers = true;
    @UriParam(label = "producer,advanced")
    private HeaderFilterStrategy headerFilterStrategy;
    @UriParam(label = "advanced", defaultValue = "true")
    private boolean propagateProperties = true;

    public ChainEndpoint(String endpointUri, ChainComponent component) {
        super(endpointUri, component);
    }

    @Override
    public ChainComponent getComponent() {
        return (ChainComponent) super.getComponent();
    }

    @Override
    public Producer createProducer() throws Exception {
        if (block) {
            return new ChainBlockingProducer(this);
        } else {
            return new ChainProducer(this);
        }
    }

    @Override
    public Consumer createConsumer(Processor processor) throws Exception {
        Consumer answer = new ChainConsumer(this, new ChainProcessor(processor, this));
        configureConsumer(answer);
        return answer;
    }

    public ChainConsumer getConsumer() {
        return getComponent().getConsumer(this);
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

    public boolean isFailIfNoConsumers() {
        return failIfNoConsumers;
    }

    /**
     * Whether the producer should fail by throwing an exception, when sending to a Direct-VM endpoint with no active
     * consumers.
     */
    public void setFailIfNoConsumers(boolean failIfNoConsumers) {
        this.failIfNoConsumers = failIfNoConsumers;
    }

    public HeaderFilterStrategy getHeaderFilterStrategy() {
        return headerFilterStrategy == null ? getComponent().getHeaderFilterStrategy() : headerFilterStrategy;
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

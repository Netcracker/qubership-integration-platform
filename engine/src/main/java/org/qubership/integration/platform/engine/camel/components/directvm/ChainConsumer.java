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

import org.apache.camel.Processor;
import org.apache.camel.Suspendable;
import org.apache.camel.support.DefaultConsumer;

/**
 * The direct-vm consumer
 */
public class ChainConsumer extends DefaultConsumer implements Suspendable {

    public ChainConsumer(ChainEndpoint endpoint, Processor processor) {
        super(endpoint, processor);
    }

    @Override
    public ChainEndpoint getEndpoint() {
        return (ChainEndpoint) super.getEndpoint();
    }

    @Override
    protected void doStart() throws Exception {
        super.doStart();
        getEndpoint().getComponent().addConsumer(getEndpoint(), this);
    }

    @Override
    protected void doStop() throws Exception {
        getEndpoint().getComponent().removeConsumer(getEndpoint(), this);
        super.doStop();
    }

    @Override
    protected void doSuspend() throws Exception {
        getEndpoint().getComponent().removeConsumer(getEndpoint(), this);
    }

    @Override
    protected void doResume() throws Exception {
        getEndpoint().getComponent().addConsumer(getEndpoint(), this);
    }

}

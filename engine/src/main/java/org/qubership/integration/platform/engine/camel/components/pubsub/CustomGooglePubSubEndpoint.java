/*
 * Copyright 2024-2026 NetCracker Technology Corporation
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

package org.qubership.integration.platform.engine.camel.components.pubsub;

import org.apache.camel.*;
import org.apache.camel.component.google.pubsub.GooglePubsubConstants;
import org.apache.camel.component.google.pubsub.GooglePubsubEndpoint;
import org.apache.camel.spi.UriEndpoint;

@UriEndpoint(firstVersion = "2.19.0", scheme = "google-pubsub", title = "Google Pubsub",
        syntax = "google-pubsub:projectId:destinationName", category = { Category.CLOUD, Category.MESSAGING },
        headersClass = GooglePubsubConstants.class)
public class CustomGooglePubSubEndpoint extends GooglePubsubEndpoint {
    public CustomGooglePubSubEndpoint(String uri, Component component) {
        super(uri, component);
    }

    @Override
    public Consumer createConsumer(Processor processor) throws Exception {
        afterPropertiesSet();
        setExchangePattern(ExchangePattern.InOnly);
        CustomGooglePubSubConsumer consumer = new CustomGooglePubSubConsumer(this, processor);
        configureConsumer(consumer);
        return consumer;
    }
}

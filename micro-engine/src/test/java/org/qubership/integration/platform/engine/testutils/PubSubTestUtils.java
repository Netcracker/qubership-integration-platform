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

package org.qubership.integration.platform.engine.testutils;

import org.apache.camel.impl.DefaultCamelContext;
import org.qubership.integration.platform.engine.camel.components.pubsub.CustomGooglePubSubComponent;
import org.qubership.integration.platform.engine.camel.components.pubsub.CustomGooglePubSubEndpoint;

public final class PubSubTestUtils {

    public static final String PROJECT_ID = "project-id";
    public static final String DESTINATION_NAME = "subscription-name";
    public static final String ENDPOINT_URI = "google-pubsub:" + PROJECT_ID + ":" + DESTINATION_NAME;

    private PubSubTestUtils() {
    }

    public static CustomGooglePubSubComponent newComponent() {
        CustomGooglePubSubComponent component = new CustomGooglePubSubComponent();
        component.setCamelContext(new DefaultCamelContext());
        return component;
    }

    public static CustomGooglePubSubEndpoint newEndpoint() {
        CustomGooglePubSubEndpoint endpoint = new CustomGooglePubSubEndpoint(ENDPOINT_URI, newComponent());
        endpoint.setProjectId(PROJECT_ID);
        endpoint.setDestinationName(DESTINATION_NAME);
        endpoint.setAuthenticate(false);
        return endpoint;
    }

    public static CustomGooglePubSubEndpoint newEndpointWithConcurrentConsumers(int concurrentConsumers) {
        CustomGooglePubSubEndpoint endpoint = newEndpoint();
        endpoint.setConcurrentConsumers(concurrentConsumers);
        return endpoint;
    }
}

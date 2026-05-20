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

import org.apache.camel.Endpoint;
import org.apache.camel.component.google.pubsub.GooglePubsubComponent;
import org.apache.camel.spi.annotations.Component;

import java.util.Map;

/*

When a subscription is no longer exist starting a subscriber throws an exception.
That causes a continuous creation of a new subscriber and addition it to a subscriber list in a loop.
The consumed memory grows until it reaches its limits.

The fix requires changes in the private methods of GooglePubSubConsumer class.
So unless this issue is fixed in Apache Camel, we have to have a local copy of CamelGooglePubSub component with the issue fixed.

Here is the link to an issue in Camel Issue Tracker:

https://issues.apache.org/jira/browse/CAMEL-22898

 */

@Component("google-pubsub")
public class CustomGooglePubSubComponent extends GooglePubsubComponent {
    @Override
    protected Endpoint createEndpoint(String uri, String remaining, Map<String, Object> parameters) throws Exception {
        String[] parts = remaining.split(":");

        if (parts.length < 2) {
            throw new IllegalArgumentException(
                    "Google PubSub Endpoint format \"projectId:destinationName[:subscriptionName]\"");
        }

        CustomGooglePubSubEndpoint pubsubEndpoint = new CustomGooglePubSubEndpoint(uri, this);
        pubsubEndpoint.setProjectId(parts[0]);
        pubsubEndpoint.setDestinationName(parts[1]);
        pubsubEndpoint.setServiceAccountKey(getServiceAccountKey());
        pubsubEndpoint.setAuthenticate(isAuthenticate());

        setProperties(pubsubEndpoint, parameters);

        return pubsubEndpoint;
    }
}

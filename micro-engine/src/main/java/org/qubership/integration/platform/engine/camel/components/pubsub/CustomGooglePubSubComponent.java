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

import com.google.api.gax.core.CredentialsProvider;
import com.google.api.gax.core.FixedCredentialsProvider;
import com.google.api.gax.core.NoCredentialsProvider;
import com.google.api.gax.grpc.InstantiatingGrpcChannelProvider;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.cloud.pubsub.v1.MessageReceiver;
import com.google.cloud.pubsub.v1.Subscriber;
import com.google.cloud.pubsub.v1.stub.PublisherStubSettings;
import io.grpc.ManagedChannelBuilder;
import org.apache.camel.Endpoint;
import org.apache.camel.component.google.pubsub.GooglePubsubComponent;
import org.apache.camel.component.google.pubsub.GooglePubsubEndpoint;
import org.apache.camel.spi.annotations.Component;
import org.apache.camel.support.ResourceHelper;
import org.apache.camel.util.ObjectHelper;
import org.apache.camel.util.StringHelper;
import org.threeten.bp.Duration;

import java.io.IOException;
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

    @Override
    public Subscriber getSubscriber(String subscriptionName, MessageReceiver messageReceiver, GooglePubsubEndpoint endpoint)
            throws IOException {
        if (StringHelper.trimToNull(getEndpoint()) == null) {
            return super.getSubscriber(subscriptionName, messageReceiver, endpoint);
        }

        return Subscriber.newBuilder(subscriptionName, messageReceiver)
                .setChannelProvider(InstantiatingGrpcChannelProvider.newBuilder()
                        .setEndpoint(getEndpoint())
                        .setChannelConfigurator(ManagedChannelBuilder::usePlaintext)
                        .build())
                .setCredentialsProvider(getSubscriberCredentialsProvider(endpoint))
                .setMaxAckExtensionPeriod(Duration.ofSeconds(endpoint.getMaxAckExtensionPeriod()))
                .build();
    }

    private CredentialsProvider getSubscriberCredentialsProvider(GooglePubsubEndpoint endpoint) throws IOException {
        if (!endpoint.isAuthenticate()) {
            return NoCredentialsProvider.create();
        }

        return FixedCredentialsProvider.create(ObjectHelper.isEmpty(endpoint.getServiceAccountKey())
                ? GoogleCredentials.getApplicationDefault()
                : ServiceAccountCredentials.fromStream(ResourceHelper.resolveMandatoryResourceAsInputStream(
                        getCamelContext(), endpoint.getServiceAccountKey()))
                        .createScoped(PublisherStubSettings.getDefaultServiceScopes()));
    }
}

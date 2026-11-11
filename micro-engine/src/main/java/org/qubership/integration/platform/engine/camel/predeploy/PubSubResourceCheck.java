package org.qubership.integration.platform.engine.camel.predeploy;

import com.google.api.gax.rpc.FixedTransportChannelProvider;
import com.google.api.gax.rpc.NotFoundException;
import com.google.api.gax.rpc.PermissionDeniedException;
import com.google.api.gax.rpc.TransportChannelProvider;
import com.google.cloud.pubsub.v1.SubscriptionAdminClient;
import com.google.cloud.pubsub.v1.SubscriptionAdminSettings;
import com.google.cloud.pubsub.v1.TopicAdminClient;
import com.google.cloud.pubsub.v1.TopicAdminSettings;
import com.google.pubsub.v1.ProjectSubscriptionName;
import com.google.pubsub.v1.TopicName;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Endpoint;
import org.apache.camel.component.google.pubsub.GooglePubsubEndpoint;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;

// The admin clients take the address and credentials from the component, so the check reaches an
// emulator the same way the route does.
@Slf4j
@ApplicationScoped
public class PubSubResourceCheck implements PredeployCheck {
    private final boolean enabled;

    @Inject
    public PubSubResourceCheck(
            @ConfigProperty(name = "cip.camel.component.pubsub.predeploy-check-enabled") boolean enabled
    ) {
        this.enabled = enabled;
    }

    @Override
    public boolean applicableTo(String scheme) {
        return enabled && "google-pubsub".equals(scheme);
    }

    @Override
    public void check(Endpoint endpoint, boolean consumer) throws IOException {
        GooglePubsubEndpoint pubsub = (GooglePubsubEndpoint) endpoint;
        String project = pubsub.getProjectId();
        String name = pubsub.getDestinationName();
        String resource = consumer ? "subscription" : "topic";
        SubscriptionAdminClient subscriptions = pubsub.getComponent().getSubscriptionAdminClient(pubsub);
        SubscriptionAdminSettings settings = subscriptions.getSettings();
        TransportChannelProvider transport = settings.getTransportChannelProvider();
        try (subscriptions) {
            if (consumer) {
                subscriptions.getSubscription(ProjectSubscriptionName.of(project, name));
            } else {
                try (TopicAdminClient topics = TopicAdminClient.create(TopicAdminSettings.newBuilder()
                        .setTransportChannelProvider(transport)
                        .setCredentialsProvider(settings.getCredentialsProvider())
                        .build())) {
                    topics.getTopic(TopicName.of(project, name));
                }
            }
        } catch (NotFoundException e) {
            throw new IllegalStateException("Pub/Sub " + resource + " '" + name + "' not found in project '"
                    + project + "'. Create it or change the element.", e);
        } catch (PermissionDeniedException e) {
            log.warn("Pub/Sub predeploy check skipped: no permission to read {} '{}' in project '{}'",
                    resource, name, project, e);
        } finally {
            // For an emulator the component builds a fixed channel, which closing the clients leaves open.
            if (transport instanceof FixedTransportChannelProvider fixed) {
                fixed.getTransportChannel().shutdown();
            }
        }
    }
}

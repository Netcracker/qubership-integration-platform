package org.qubership.integration.platform.engine.camel.predeploy;

import com.google.api.gax.core.NoCredentialsProvider;
import com.google.api.gax.rpc.FixedTransportChannelProvider;
import com.google.api.gax.rpc.NotFoundException;
import com.google.api.gax.rpc.PermissionDeniedException;
import com.google.api.gax.rpc.TransportChannel;
import com.google.api.gax.rpc.TransportChannelProvider;
import com.google.cloud.pubsub.v1.SubscriptionAdminClient;
import com.google.cloud.pubsub.v1.SubscriptionAdminSettings;
import com.google.cloud.pubsub.v1.TopicAdminClient;
import com.google.cloud.pubsub.v1.TopicAdminSettings;
import com.google.pubsub.v1.ProjectSubscriptionName;
import com.google.pubsub.v1.TopicName;
import org.apache.camel.component.google.pubsub.GooglePubsubComponent;
import org.apache.camel.component.google.pubsub.GooglePubsubEndpoint;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class PubSubResourceCheckTest {

    @Mock
    private GooglePubsubEndpoint endpoint;

    @Mock
    private GooglePubsubComponent component;

    @Mock
    private SubscriptionAdminClient subscriptions;

    @Mock
    private SubscriptionAdminSettings settings;

    @Mock
    private TransportChannelProvider transport;

    private final PubSubResourceCheck check = new PubSubResourceCheck(true);

    @Test
    void shouldApplyToGooglePubsubSchemeOnlyWhenEnabled() {
        assertTrue(check.applicableTo("google-pubsub"));
        assertFalse(check.applicableTo("kafka-custom"));
        assertFalse(new PubSubResourceCheck(false).applicableTo("google-pubsub"));
    }

    @Test
    void shouldFailWhenConsumedSubscriptionIsMissing() throws Exception {
        givenSubscriptionClient(transport);
        NotFoundException notFound = mock(NotFoundException.class);
        when(subscriptions.getSubscription(ProjectSubscriptionName.of("project", "orders"))).thenThrow(notFound);

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> check.check(endpoint, true));

        assertEquals("Pub/Sub subscription 'orders' not found in project 'project'. Create it or change the element.",
                failure.getMessage());
        assertSame(notFound, failure.getCause());
        verify(subscriptions).close();
    }

    @Test
    void shouldFailWhenTopicIsMissingUsingSubscriptionClientTransport() throws Exception {
        givenSubscriptionClient(transport);
        when(settings.getCredentialsProvider()).thenReturn(NoCredentialsProvider.create());
        TopicAdminClient topics = mock(TopicAdminClient.class);
        when(topics.getTopic(TopicName.of("project", "orders"))).thenThrow(mock(NotFoundException.class));

        try (MockedStatic<TopicAdminClient> topicClientStatic = mockStatic(TopicAdminClient.class)) {
            ArgumentCaptor<TopicAdminSettings> topicSettings = ArgumentCaptor.forClass(TopicAdminSettings.class);
            topicClientStatic.when(() -> TopicAdminClient.create(any(TopicAdminSettings.class))).thenReturn(topics);

            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> check.check(endpoint, false));

            assertEquals("Pub/Sub topic 'orders' not found in project 'project'. Create it or change the element.",
                    failure.getMessage());
            topicClientStatic.verify(() -> TopicAdminClient.create(topicSettings.capture()));
            assertSame(transport, topicSettings.getValue().getTransportChannelProvider());
        }
        verify(topics).close();
    }

    @Test
    void shouldSkipWhenAccountMayNotReadSubscription() throws Exception {
        givenSubscriptionClient(transport);
        when(subscriptions.getSubscription(ProjectSubscriptionName.of("project", "orders")))
                .thenThrow(mock(PermissionDeniedException.class));

        assertDoesNotThrow(() -> check.check(endpoint, true));
    }

    @Test
    void shouldShutDownFixedEmulatorChannel() throws Exception {
        TransportChannel channel = mock(TransportChannel.class);
        givenSubscriptionClient(FixedTransportChannelProvider.create(channel));

        check.check(endpoint, true);

        verify(channel).shutdown();
    }

    private void givenSubscriptionClient(TransportChannelProvider provider) throws Exception {
        when(subscriptions.getSettings()).thenReturn(settings);
        when(settings.getTransportChannelProvider()).thenReturn(provider);
        when(endpoint.getProjectId()).thenReturn("project");
        when(endpoint.getDestinationName()).thenReturn("orders");
        when(endpoint.getComponent()).thenReturn(component);
        when(component.getSubscriptionAdminClient(endpoint)).thenReturn(subscriptions);
    }
}

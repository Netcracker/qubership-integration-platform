package org.qubership.integration.platform.engine.jms.weblogic;

import jakarta.jms.Destination;
import jakarta.jms.JMSException;
import jakarta.jms.Queue;
import jakarta.jms.Session;
import jakarta.jms.Topic;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.springframework.jndi.JndiTemplate;

import javax.naming.NamingException;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class JndiDestinationResolverTest {

    private static final String DESTINATION_NAME = "jms/test-destination";

    @Mock
    private JndiTemplate jndiTemplate;

    @Mock
    private Session session;

    @Mock
    private Queue queue;

    @Mock
    private Queue anotherQueue;

    @Mock
    private Topic topic;

    @Mock
    private Topic anotherTopic;

    private JndiDestinationResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new JndiDestinationResolver();
        resolver.setJndiTemplate(jndiTemplate);
    }

    @Test
    void shouldResolveQueueFromJndiAndCacheDestinationWhenCacheIsEnabled() throws Exception {
        when(jndiTemplate.lookup(DESTINATION_NAME, Destination.class)).thenReturn(queue);

        Destination firstResult = resolver.resolveDestinationName(session, DESTINATION_NAME, false);
        Destination secondResult = resolver.resolveDestinationName(session, DESTINATION_NAME, false);

        assertSame(queue, firstResult);
        assertSame(queue, secondResult);
        verify(jndiTemplate, times(1)).lookup(DESTINATION_NAME, Destination.class);
    }

    @Test
    void shouldResolveTopicFromJndiWhenPubSubDomainIsTrue() throws Exception {
        when(jndiTemplate.lookup(DESTINATION_NAME, Destination.class)).thenReturn(topic);

        Destination result = resolver.resolveDestinationName(session, DESTINATION_NAME, true);

        assertSame(topic, result);
    }

    @Test
    void shouldResolveDestinationFromJndiEveryTimeWhenCacheIsDisabled() throws Exception {
        resolver.setCache(false);

        when(jndiTemplate.lookup(DESTINATION_NAME, Destination.class)).thenReturn(queue, anotherQueue);

        Destination firstResult = resolver.resolveDestinationName(session, DESTINATION_NAME, false);
        Destination secondResult = resolver.resolveDestinationName(session, DESTINATION_NAME, false);

        assertSame(queue, firstResult);
        assertSame(anotherQueue, secondResult);
        verify(jndiTemplate, times(2)).lookup(DESTINATION_NAME, Destination.class);
    }

    @Test
    void shouldRemoveDestinationFromCache() throws Exception {
        when(jndiTemplate.lookup(DESTINATION_NAME, Destination.class)).thenReturn(queue, anotherQueue);

        Destination firstResult = resolver.resolveDestinationName(session, DESTINATION_NAME, false);

        resolver.removeFromCache(DESTINATION_NAME);

        Destination secondResult = resolver.resolveDestinationName(session, DESTINATION_NAME, false);

        assertSame(queue, firstResult);
        assertSame(anotherQueue, secondResult);
        verify(jndiTemplate, times(2)).lookup(DESTINATION_NAME, Destination.class);
    }

    @Test
    void shouldClearCache() throws Exception {
        when(jndiTemplate.lookup(DESTINATION_NAME, Destination.class)).thenReturn(topic, anotherTopic);

        Destination firstResult = resolver.resolveDestinationName(session, DESTINATION_NAME, true);

        resolver.clearCache();

        Destination secondResult = resolver.resolveDestinationName(session, DESTINATION_NAME, true);

        assertSame(topic, firstResult);
        assertSame(anotherTopic, secondResult);
        verify(jndiTemplate, times(2)).lookup(DESTINATION_NAME, Destination.class);
    }

    @Test
    void shouldResolveQueueDynamicallyWhenJndiLookupFailsAndFallbackIsEnabled() throws Exception {
        NamingException namingException = new NamingException("Destination not found");

        resolver.setFallbackToDynamicDestination(true);

        when(jndiTemplate.lookup(DESTINATION_NAME, Destination.class)).thenThrow(namingException);
        when(session.createQueue(DESTINATION_NAME)).thenReturn(queue);

        Destination result = resolver.resolveDestinationName(session, DESTINATION_NAME, false);

        assertSame(queue, result);
    }

    @Test
    void shouldResolveTopicDynamicallyWhenJndiLookupFailsAndFallbackIsEnabledForPubSubDomain() throws Exception {
        NamingException namingException = new NamingException("Destination not found");

        resolver.setFallbackToDynamicDestination(true);

        when(jndiTemplate.lookup(DESTINATION_NAME, Destination.class)).thenThrow(namingException);
        when(session.createTopic(DESTINATION_NAME)).thenReturn(topic);

        Destination result = resolver.resolveDestinationName(session, DESTINATION_NAME, true);

        assertSame(topic, result);
    }

    @Test
    void shouldThrowRuntimeExceptionWhenJndiLookupFailsAndFallbackIsDisabled() throws Exception {
        NamingException namingException = new NamingException("Destination not found");

        when(jndiTemplate.lookup(DESTINATION_NAME, Destination.class)).thenThrow(namingException);

        RuntimeException result = assertThrows(
            RuntimeException.class,
            () -> resolver.resolveDestinationName(session, DESTINATION_NAME, false)
        );

        assertSame(namingException, result.getCause());
    }

    @Test
    void shouldThrowRuntimeExceptionWhenQueueExpectedButResolvedDestinationIsTopic() throws Exception {
        when(jndiTemplate.lookup(DESTINATION_NAME, Destination.class)).thenReturn(topic);

        assertThrows(
            RuntimeException.class,
            () -> resolver.resolveDestinationName(session, DESTINATION_NAME, false)
        );
    }

    @Test
    void shouldThrowRuntimeExceptionWhenTopicExpectedButResolvedDestinationIsQueue() throws Exception {
        when(jndiTemplate.lookup(DESTINATION_NAME, Destination.class)).thenReturn(queue);

        assertThrows(
            RuntimeException.class,
            () -> resolver.resolveDestinationName(session, DESTINATION_NAME, true)
        );
    }

    @Test
    void shouldValidateCachedDestinationAgainstRequestedDomain() throws Exception {
        when(jndiTemplate.lookup(DESTINATION_NAME, Destination.class)).thenReturn(queue);

        Destination result = resolver.resolveDestinationName(session, DESTINATION_NAME, false);

        assertSame(queue, result);
        assertThrows(
            RuntimeException.class,
            () -> resolver.resolveDestinationName(session, DESTINATION_NAME, true)
        );
        verify(jndiTemplate, times(1)).lookup(DESTINATION_NAME, Destination.class);
    }

    @Test
    void shouldPropagateJmsExceptionWhenDynamicQueueResolutionFails() throws Exception {
        NamingException namingException = new NamingException("Destination not found");
        JMSException jmsException = new JMSException("Failed to create dynamic queue");

        resolver.setFallbackToDynamicDestination(true);

        when(jndiTemplate.lookup(DESTINATION_NAME, Destination.class)).thenThrow(namingException);
        when(session.createQueue(DESTINATION_NAME)).thenThrow(jmsException);

        JMSException result = assertThrows(
            JMSException.class,
            () -> resolver.resolveDestinationName(session, DESTINATION_NAME, false)
        );

        assertSame(jmsException, result);
    }
}

package org.qubership.integration.platform.engine.camel.components.pubsub;

import org.apache.camel.Processor;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.qubership.integration.platform.engine.testutils.PubSubTestUtils;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class CustomGooglePubSubConsumerTest {

    @Test
    void shouldSubmitSubscriberWrapperForEachConcurrentConsumerWhenStarted() throws Exception {
        CustomGooglePubSubEndpoint endpoint = spy(PubSubTestUtils.newEndpointWithConcurrentConsumers(3));
        Processor processor = mock(Processor.class);
        CustomGooglePubSubConsumer consumer = new CustomGooglePubSubConsumer(endpoint, processor);
        ExecutorService executor = mock(ExecutorService.class);
        Future<?> submittedTask = mock(Future.class);

        doReturn(executor).when(endpoint).createExecutor(consumer);
        doReturn(submittedTask).when(executor).submit(any(Runnable.class));

        consumer.start();
        doReturn(null).when(endpoint).getCamelContext();

        try {
            verify(endpoint).createExecutor(consumer);
            verify(executor, times(3)).submit(any(Runnable.class));
        } finally {
            consumer.stop();
        }

        verify(executor).shutdownNow();
    }
}

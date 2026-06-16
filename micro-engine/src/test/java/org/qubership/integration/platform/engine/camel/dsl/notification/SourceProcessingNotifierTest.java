package org.qubership.integration.platform.engine.camel.dsl.notification;

import org.apache.camel.spi.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.util.Collections;
import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class SourceProcessingNotifierTest {

    @Mock
    private SourceProcessingListener firstListener;

    @Mock
    private SourceProcessingListener secondListener;

    @Mock
    private Resource resource;

    private SourceProcessingNotifier notifier;

    @BeforeEach
    void setUp() {
        notifier = new SourceProcessingNotifier();
        notifier.listeners = List.of(firstListener, secondListener);
    }

    @Test
    void shouldNotifyAllListenersWhenSourceProcessingStarted() {
        notifier.notifySourceProcessingStarted(resource);

        verify(firstListener).onSourceProcessingStart(resource);
        verify(secondListener).onSourceProcessingStart(resource);
    }

    @Test
    void shouldNotifyAllListenersWhenSourceLoaded() {
        notifier.notifySourceLoaded(resource);

        verify(firstListener).onSourceLoaded(resource);
        verify(secondListener).onSourceLoaded(resource);
    }

    @Test
    void shouldNotifyAllListenersWhenSourceLoadFailed() {
        Exception exception = new RuntimeException("Source loading failed");

        notifier.notifySourceLoadFailed(resource, exception);

        verify(firstListener).onSourceLoadFailed(resource, exception);
        verify(secondListener).onSourceLoadFailed(resource, exception);
    }

    @Test
    void shouldDoNothingWhenListenersAreEmpty() {
        notifier.listeners = Collections.emptyList();

        notifier.notifySourceProcessingStarted(resource);
        notifier.notifySourceLoaded(resource);
        notifier.notifySourceLoadFailed(resource, new RuntimeException("Source loading failed"));

        verifyNoInteractions(firstListener, secondListener);
    }
}

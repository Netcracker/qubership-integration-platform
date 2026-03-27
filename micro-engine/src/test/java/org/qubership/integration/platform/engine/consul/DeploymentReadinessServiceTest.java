package org.qubership.integration.platform.engine.consul;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.configuration.EventClassesContainerWrapper;
import org.qubership.integration.platform.engine.events.UpdateEvent;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class DeploymentReadinessServiceTest {

    private DeploymentReadinessService service;

    @Mock
    EventClassesContainerWrapper eventClassesContainerWrapper;

    @Test
    void shouldNotBeReadyWhenCreated() {
        UpdateEvent event = mock(UpdateEvent.class);

        initService(Set.of(eventClass(event)));

        assertFalse(service.isReadyForDeploy());
        assertFalse(service.isInitialized());
    }

    @Test
    void shouldBecomeReadyWhenInitialRequiredEventReceived() {
        UpdateEvent event = mock(UpdateEvent.class);
        when(event.isInitialUpdate()).thenReturn(true);

        initService(Set.of(eventClass(event)));

        service.onUpdateEvent(event);

        assertTrue(service.isReadyForDeploy());
    }

    @Test
    void shouldNotBecomeReadyWhenRequiredEventIsNotInitial() {
        UpdateEvent event = mock(UpdateEvent.class);
        when(event.isInitialUpdate()).thenReturn(false);

        initService(Set.of(eventClass(event)));

        service.onUpdateEvent(event);

        assertFalse(service.isReadyForDeploy());
    }

    @Test
    void shouldNotBecomeReadyWhenNotAllRequiredEventsReceived() {
        UpdateEvent event = mock(UpdateEvent.class);
        when(event.isInitialUpdate()).thenReturn(true);

        initService(Set.of(eventClass(event), unknownUpdateEventClass()));

        service.onUpdateEvent(event);

        assertFalse(service.isReadyForDeploy());
    }

    @Test
    void shouldIgnoreInitialEventWhenEventClassIsNotRequired() {
        UpdateEvent event = mock(UpdateEvent.class);
        when(event.isInitialUpdate()).thenReturn(true);

        initService(Set.of(unknownUpdateEventClass()));

        service.onUpdateEvent(event);

        assertFalse(service.isReadyForDeploy());
    }

    @Test
    void shouldNotFailWhenApplicationStartedAndThreadInterrupted() {
        initService(Set.of(unknownUpdateEventClass()));
        Thread.currentThread().interrupt();

        try {
            service.onApplicationStarted(null);
        } finally {
            Thread.interrupted();
        }

        assertFalse(service.isReadyForDeploy());
    }

    @Test
    void shouldLogRequiredEventClassesWhenDebugEnabled() {
        org.jboss.logmanager.Logger logger =
                org.jboss.logmanager.Logger.getLogger(DeploymentReadinessService.class.getName());
        java.util.logging.Level previousLevel = logger.getLevel();

        try {
            logger.setLevel(org.jboss.logmanager.Level.DEBUG);

            when(eventClassesContainerWrapper.getEventClasses()).thenReturn(
                    Set.of(TestUpdateEvent.class, AnotherTestUpdateEvent.class)
            );

            service = new DeploymentReadinessService(eventClassesContainerWrapper);

            verify(eventClassesContainerWrapper, times(2)).getEventClasses();
            assertFalse(service.isReadyForDeploy());
        } finally {
            logger.setLevel(previousLevel);
        }
    }

    private static class TestUpdateEvent extends UpdateEvent {
        public TestUpdateEvent(Object source, boolean initialUpdate) {
            super(source, initialUpdate);
        }

        @Override
        public boolean isInitialUpdate() {
            return true;
        }
    }

    private static class AnotherTestUpdateEvent extends UpdateEvent {
        public AnotherTestUpdateEvent(Object source, boolean initialUpdate) {
            super(source, initialUpdate);
        }

        @Override
        public boolean isInitialUpdate() {
            return true;
        }
    }

    private void initService(Set<Class<? extends UpdateEvent>> eventClasses) {
        when(eventClassesContainerWrapper.getEventClasses()).thenReturn(eventClasses);
        service = new DeploymentReadinessService(eventClassesContainerWrapper);
    }

    private static Class<? extends UpdateEvent> eventClass(UpdateEvent event) {
        return event.getClass();
    }

    @SuppressWarnings("unchecked")
    private static Class<? extends UpdateEvent> unknownUpdateEventClass() {
        return (Class<? extends UpdateEvent>) (Class<?>) String.class;
    }
}

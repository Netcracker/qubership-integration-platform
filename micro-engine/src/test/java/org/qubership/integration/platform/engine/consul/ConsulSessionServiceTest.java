package org.qubership.integration.platform.engine.consul;

import io.smallrye.mutiny.Uni;
import io.vertx.ext.consul.Session;
import io.vertx.ext.consul.SessionBehavior;
import io.vertx.ext.consul.SessionOptions;
import io.vertx.mutiny.core.eventbus.EventBus;
import io.vertx.mutiny.ext.consul.ConsulClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class ConsulSessionServiceTest {

    private static final String FIRST_SESSION_ID = "8d4d10b6-9b8c-4ad0-a133-fb85cf9d2865";
    private static final String SECOND_SESSION_ID = "f7f1e7e1-6f8d-4f6e-b74c-d6f0af3df482";

    private ConsulSessionService service;

    @Mock
    ConsulClient consulClient;
    @Mock
    EventBus eventBus;

    @BeforeEach
    void setUp() {
        service = new ConsulSessionService();
        service.consulClientSupplier = () -> consulClient;
        service.eventBus = eventBus;
    }

    @Test
    void shouldCreateSessionAndPublishEventWhenGetOrCreateSessionCalledFirstTime() {
        when(consulClient.createSessionWithOptions(any(SessionOptions.class)))
                .thenReturn(Uni.createFrom().item(FIRST_SESSION_ID));

        String result = service.getOrCreateSession();

        assertEquals(FIRST_SESSION_ID, result);
        verify(eventBus).publish(ConsulSessionService.CREATE_SESSION_EVENT, FIRST_SESSION_ID);

        ArgumentCaptor<SessionOptions> optionsCaptor = ArgumentCaptor.forClass(SessionOptions.class);
        verify(consulClient).createSessionWithOptions(optionsCaptor.capture());

        SessionOptions options = optionsCaptor.getValue();
        assertTrue(options.getName().startsWith("qip-engine-session-"));
        assertEquals(60L, options.getTtl());
        assertEquals(SessionBehavior.DELETE, options.getBehavior());
    }

    @Test
    void shouldReturnExistingSessionWhenGetOrCreateSessionCalledAndSessionAlreadyExists() {
        when(consulClient.createSessionWithOptions(any(SessionOptions.class)))
                .thenReturn(Uni.createFrom().item(FIRST_SESSION_ID));

        String firstResult = service.getOrCreateSession();
        String secondResult = service.getOrCreateSession();

        assertEquals(FIRST_SESSION_ID, firstResult);
        assertEquals(FIRST_SESSION_ID, secondResult);
        verify(consulClient, times(1)).createSessionWithOptions(any(SessionOptions.class));
        verify(consulClient, never()).renewSession(anyString());
        verify(consulClient, never()).destroySession(anyString());
    }

    @Test
    void shouldRenewSessionWhenCreateOrRenewSessionCalledAndSessionAlreadyExists() {
        when(consulClient.createSessionWithOptions(any(SessionOptions.class)))
                .thenReturn(Uni.createFrom().item(FIRST_SESSION_ID));
        when(consulClient.renewSession(FIRST_SESSION_ID))
                .thenReturn(Uni.createFrom().item(new Session()));

        service.getOrCreateSession();
        service.createOrRenewSession();

        verify(consulClient).renewSession(FIRST_SESSION_ID);
        verify(eventBus, times(1)).publish(ConsulSessionService.CREATE_SESSION_EVENT, FIRST_SESSION_ID);
    }

    @Test
    void shouldDeletePreviousSessionAndCreateNewOneWhenPreviousSessionStoredAfterRenewFailure() {
        when(consulClient.createSessionWithOptions(any(SessionOptions.class)))
                .thenReturn(
                        Uni.createFrom().item(FIRST_SESSION_ID),
                        Uni.createFrom().item(SECOND_SESSION_ID)
                );
        when(consulClient.renewSession(FIRST_SESSION_ID))
                .thenReturn(Uni.createFrom().failure(new RuntimeException("Renew failed")));
        when(consulClient.destroySession(FIRST_SESSION_ID))
                .thenReturn(Uni.createFrom().voidItem());

        service.getOrCreateSession();
        service.createOrRenewSession();
        String result = service.getOrCreateSession();

        assertEquals(SECOND_SESSION_ID, result);
        verify(consulClient).destroySession(FIRST_SESSION_ID);
        verify(eventBus).publish(ConsulSessionService.CREATE_SESSION_EVENT, FIRST_SESSION_ID);
        verify(eventBus).publish(ConsulSessionService.CREATE_SESSION_EVENT, SECOND_SESSION_ID);
    }

    @Test
    void shouldCreateNewSessionWithoutDeletingPreviousWhenRenewFailsWithSessionNotFoundMessage() {
        when(consulClient.createSessionWithOptions(any(SessionOptions.class)))
                .thenReturn(
                        Uni.createFrom().item(FIRST_SESSION_ID),
                        Uni.createFrom().item(SECOND_SESSION_ID)
                );
        when(consulClient.renewSession(FIRST_SESSION_ID))
                .thenReturn(Uni.createFrom().failure(
                        new RuntimeException("Session id " + FIRST_SESSION_ID + " not found")
                ));

        service.getOrCreateSession();
        service.createOrRenewSession();
        String result = service.getOrCreateSession();

        assertEquals(SECOND_SESSION_ID, result);
        verify(consulClient, never()).destroySession(anyString());
        verify(eventBus).publish(ConsulSessionService.CREATE_SESSION_EVENT, FIRST_SESSION_ID);
        verify(eventBus).publish(ConsulSessionService.CREATE_SESSION_EVENT, SECOND_SESSION_ID);
    }

    @Test
    void shouldReturnNullWhenCreateSessionFails() {
        when(consulClient.createSessionWithOptions(any(SessionOptions.class)))
                .thenReturn(Uni.createFrom().failure(new RuntimeException("Create failed")));

        String result = service.getOrCreateSession();

        assertNull(result);
        verify(eventBus, never()).publish(anyString(), any());
    }
}

/*
 * Copyright 2024-2025 NetCracker Technology Corporation
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

package org.qubership.integration.platform.engine.consul;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.engine.configuration.ServerConfiguration;
import org.qubership.integration.platform.engine.events.ConsulSessionCreatedEvent;
import org.qubership.integration.platform.engine.model.deployment.engine.EngineInfo;
import org.springframework.context.ApplicationEventPublisher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConsulServiceTest {

    private static final String FIRST_SESSION_ID = "first-session-id";
    private static final String SECOND_SESSION_ID = "second-session-id";

    private ConsulClient client;
    private ApplicationEventPublisher applicationEventPublisher;
    private ConsulService consulService;

    @BeforeEach
    void setUp() {
        client = mock(ConsulClient.class);
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        applicationEventPublisher = mock(ApplicationEventPublisher.class);

        ServerConfiguration serverConfiguration = mock(ServerConfiguration.class);
        when(serverConfiguration.getEngineInfo()).thenReturn(EngineInfo.builder()
                .engineDeploymentName("engine")
                .domain("domain")
                .host("host")
                .build());

        consulService = new ConsulService(
                client,
                serverConfiguration,
                objectMapper,
                applicationEventPublisher,
                new ConsulKeyValidator());
    }

    @Test
    void createOrRenewSessionCreatesSessionAndPublishesEventWhenNoActiveSession() {
        when(client.createSession(anyString(), anyString(), anyString()))
                .thenReturn(FIRST_SESSION_ID);

        consulService.createOrRenewSession();

        assertEquals(FIRST_SESSION_ID, consulService.getActiveSessionId());
        verify(applicationEventPublisher).publishEvent(any(ConsulSessionCreatedEvent.class));
    }

    @Test
    void createOrRenewSessionRenewsWhenActiveSessionExists() {
        when(client.createSession(anyString(), anyString(), anyString()))
                .thenReturn(FIRST_SESSION_ID);

        consulService.createOrRenewSession();
        consulService.createOrRenewSession();

        assertEquals(FIRST_SESSION_ID, consulService.getActiveSessionId());
        verify(client).renewSession(FIRST_SESSION_ID);
        verify(client, times(1)).createSession(anyString(), anyString(), anyString());
    }

    @Test
    void createOrRenewSessionDeletesPreviousSessionBeforeCreatingNewOne() {
        when(client.createSession(anyString(), anyString(), anyString()))
                .thenReturn(FIRST_SESSION_ID, SECOND_SESSION_ID);
        doThrow(new InvalidConsulSessionException(FIRST_SESSION_ID, new RuntimeException("not found")))
                .when(client).renewSession(FIRST_SESSION_ID);

        consulService.createOrRenewSession();
        consulService.createOrRenewSession();
        consulService.createOrRenewSession();

        assertEquals(SECOND_SESSION_ID, consulService.getActiveSessionId());
        verify(client).deleteSession(FIRST_SESSION_ID);
        verify(client, times(2)).createSession(anyString(), anyString(), anyString());
        verify(applicationEventPublisher, times(2)).publishEvent(any(ConsulSessionCreatedEvent.class));
    }

    @Test
    void createOrRenewSessionRetriesWhenPreviousSessionDeletionFails() {
        when(client.createSession(anyString(), anyString(), anyString()))
                .thenReturn(FIRST_SESSION_ID);
        doThrow(new InvalidConsulSessionException(FIRST_SESSION_ID, new RuntimeException("not found")))
                .when(client).renewSession(FIRST_SESSION_ID);
        doThrow(new RuntimeException("delete failed")).when(client).deleteSession(FIRST_SESSION_ID);

        consulService.createOrRenewSession();
        consulService.createOrRenewSession();
        consulService.createOrRenewSession();

        assertNull(consulService.getActiveSessionId());
        verify(client, times(1)).createSession(anyString(), anyString(), anyString());
        verify(client, times(1)).deleteSession(FIRST_SESSION_ID);
        verify(applicationEventPublisher, times(1)).publishEvent(any(ConsulSessionCreatedEvent.class));
    }

    @Test
    void createOrRenewSessionClearsActiveSessionWhenRenewReturnsInvalidSession() {
        when(client.createSession(anyString(), anyString(), anyString()))
                .thenReturn(FIRST_SESSION_ID);
        doThrow(new InvalidConsulSessionException(FIRST_SESSION_ID, new RuntimeException("not found")))
                .when(client).renewSession(FIRST_SESSION_ID);

        consulService.createOrRenewSession();
        consulService.createOrRenewSession();

        assertNull(consulService.getActiveSessionId());
        verify(client).renewSession(FIRST_SESSION_ID);
    }

    @Test
    void createOrRenewSessionKeepsActiveSessionWhenRenewFailsWithOtherError() {
        when(client.createSession(anyString(), anyString(), anyString()))
                .thenReturn(FIRST_SESSION_ID);
        doThrow(new RuntimeException("renew failed")).when(client).renewSession(FIRST_SESSION_ID);

        consulService.createOrRenewSession();
        consulService.createOrRenewSession();

        assertEquals(FIRST_SESSION_ID, consulService.getActiveSessionId());
        verify(client).renewSession(FIRST_SESSION_ID);
    }

    @Test
    void createOrRenewSessionKeepsActiveSessionNullWhenCreateFails() {
        doThrow(new RuntimeException("create failed"))
                .when(client).createSession(anyString(), anyString(), anyString());

        consulService.createOrRenewSession();

        assertNull(consulService.getActiveSessionId());
        verify(applicationEventPublisher, never()).publishEvent(any());
    }
}

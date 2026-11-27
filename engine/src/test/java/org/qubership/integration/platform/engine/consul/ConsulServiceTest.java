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

import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.engine.configuration.ServerConfiguration;
import org.qubership.integration.platform.engine.events.ConsulSessionCreatedEvent;
import org.qubership.integration.platform.engine.model.consul.KeyResponse;
import org.qubership.integration.platform.engine.model.deployment.engine.EngineInfo;
import org.qubership.integration.platform.engine.model.logging.LogLoggingLevel;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConsulServiceTest {

    private static final String CHAIN_SETTINGS_KEY = "config/engine/runtime-configurations/chains";
    private static final String FIRST_SESSION_ID = "first-session-id";
    private static final String SECOND_SESSION_ID = "second-session-id";

    private ConsulClient client;
    private ApplicationEventPublisher applicationEventPublisher;
    private ConsulService consulService;

    @BeforeEach
    void setUp() {
        client = mock(ConsulClient.class);
        ObjectMapper objectMapper = new ObjectMapper();
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
        ReflectionTestUtils.setField(consulService, "keyPrefix", "config");
        ReflectionTestUtils.setField(consulService, "keyEngineConfigRoot", "/engine");
        ReflectionTestUtils.setField(consulService, "keyRuntimeConfigurations", "/runtime-configurations");
        ReflectionTestUtils.setField(consulService, "keyChains", "/chains");
    }

    @Test
    void readsLatestSettingsWithoutAdvancingTheWatchIndex() {
        String settings = "{\"logLoggingLevel\":\"INFO\"}";
        KeyResponse chain = KeyResponse.builder()
                .key(CHAIN_SETTINGS_KEY + "/chain")
                .value(Base64.getEncoder().encodeToString(settings.getBytes(StandardCharsets.UTF_8)))
                .build();
        when(client.waitForKVChanges(CHAIN_SETTINGS_KEY, false, 0, "20s"))
                .thenReturn(Pair.of(10L, List.of(chain)), Pair.of(12L, List.of(chain)));
        when(client.waitForKVChanges(CHAIN_SETTINGS_KEY, false, 10, "20s"))
                .thenReturn(Pair.of(12L, List.of(chain)));

        consulService.waitForChainRuntimeConfig();
        var properties = consulService.readChainRuntimeConfig();
        var update = consulService.waitForChainRuntimeConfig();

        assertEquals(LogLoggingLevel.INFO, properties.get("chain").getLogLoggingLevel());
        assertEquals(Boolean.TRUE, update.getLeft());
        var requests = inOrder(client);
        requests.verify(client, times(2)).waitForKVChanges(CHAIN_SETTINGS_KEY, false, 0, "20s");
        requests.verify(client).waitForKVChanges(CHAIN_SETTINGS_KEY, false, 10, "20s");
    }

    @Test
    void readsEmptySettingsWhenConsulHasNoRuntimeConfiguration() {
        when(client.waitForKVChanges(CHAIN_SETTINGS_KEY, false, 0, "20s"))
                .thenThrow(new KVNotFoundException());

        assertEquals(Map.of(), consulService.readChainRuntimeConfig());
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

    @Test
    void destroySessionDeletesActiveSession() {
        when(client.createSession(anyString(), anyString(), anyString()))
                .thenReturn(FIRST_SESSION_ID);
        consulService.createOrRenewSession();

        consulService.destroySession();

        verify(client).deleteSession(FIRST_SESSION_ID);
    }

    @Test
    void destroySessionSkipsDeleteWhenNoActiveSession() {
        consulService.destroySession();

        verify(client, never()).deleteSession(anyString());
    }

    @Test
    void destroySessionStopsWaitingWhenConsulDoesNotAnswer() {
        when(client.createSession(anyString(), anyString(), anyString()))
                .thenReturn(FIRST_SESSION_ID);
        CountDownLatch consulAnswers = new CountDownLatch(1);
        doAnswer(invocation -> {
            consulAnswers.await();
            return null;
        }).when(client).deleteSession(FIRST_SESSION_ID);
        consulService.createOrRenewSession();

        try {
            CompletionException thrown = assertTimeoutPreemptively(Duration.ofSeconds(10),
                    () -> assertThrows(CompletionException.class, consulService::destroySession));
            assertInstanceOf(TimeoutException.class, thrown.getCause());
        } finally {
            consulAnswers.countDown();
        }
    }

    @Test
    void destroySessionStopsWaitingWhileRenewalHoldsTheLock() throws InterruptedException {
        when(client.createSession(anyString(), anyString(), anyString()))
                .thenReturn(FIRST_SESSION_ID);
        CountDownLatch renewalStarted = new CountDownLatch(1);
        CountDownLatch consulAnswers = new CountDownLatch(1);
        doAnswer(invocation -> {
            renewalStarted.countDown();
            consulAnswers.await();
            return null;
        }).when(client).renewSession(FIRST_SESSION_ID);
        consulService.createOrRenewSession();
        Thread renewal = new Thread(consulService::createOrRenewSession);
        renewal.start();
        renewalStarted.await();

        try {
            CompletionException thrown = assertTimeoutPreemptively(Duration.ofSeconds(10),
                    () -> assertThrows(CompletionException.class, consulService::destroySession));
            assertInstanceOf(TimeoutException.class, thrown.getCause());
        } finally {
            consulAnswers.countDown();
            renewal.join();
        }
    }
}

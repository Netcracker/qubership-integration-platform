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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConsulClientTest {

    private static final String SESSION_ID = "session-123";

    private RestTemplate restTemplate;
    private ConsulClient client;

    @BeforeEach
    void setUp() {
        restTemplate = mock(RestTemplate.class);
        client = new ConsulClient(restTemplate, "http://consul:8500");
    }

    @Test
    void renewSessionSucceedsWhenConsulReturnsOk() {
        when(restTemplate.exchange(anyString(), any(), any(), any(Class.class)))
                .thenReturn(new ResponseEntity<>("", HttpStatus.OK));

        assertDoesNotThrow(() -> client.renewSession(SESSION_ID));
    }

    @Test
    void renewSessionThrowsInvalidConsulSessionExceptionWhenSessionNotFound() {
        HttpClientErrorException notFound = HttpClientErrorException.create(
                HttpStatus.NOT_FOUND, "Not Found", HttpHeaders.EMPTY, null, null);
        when(restTemplate.exchange(anyString(), any(), any(), any(Class.class)))
                .thenThrow(notFound);

        InvalidConsulSessionException exception = assertThrows(
                InvalidConsulSessionException.class, () -> client.renewSession(SESSION_ID));

        assertEquals("Consul session is invalid or expired: " + SESSION_ID, exception.getMessage());
        assertEquals(notFound, exception.getCause());
    }

    @Test
    void renewSessionRethrowsOtherHttpClientErrors() {
        HttpClientErrorException badRequest = HttpClientErrorException.create(
                HttpStatus.BAD_REQUEST, "Bad Request", HttpHeaders.EMPTY, null, null);
        when(restTemplate.exchange(anyString(), any(), any(), any(Class.class)))
                .thenThrow(badRequest);

        HttpClientErrorException exception = assertThrows(
                HttpClientErrorException.class, () -> client.renewSession(SESSION_ID));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
    }

    @Test
    void renewSessionThrowsWhenConsulReturnsNonOkStatus() {
        when(restTemplate.exchange(anyString(), any(), any(), any(Class.class)))
                .thenReturn(new ResponseEntity<>("error", HttpStatus.INTERNAL_SERVER_ERROR));

        assertThrows(RuntimeException.class, () -> client.renewSession(SESSION_ID));
    }

    @Test
    void deleteSessionReturnsWhenSessionAlreadyGone() {
        when(restTemplate.exchange(contains("destroy"), any(), any(), any(Class.class), anyMap()))
                .thenReturn(new ResponseEntity<>("false", HttpStatus.OK));

        assertDoesNotThrow(() -> client.deleteSession(SESSION_ID));
    }

    @Test
    void deleteSessionSucceedsWhenConsulReturnsTrue() {
        when(restTemplate.exchange(contains("destroy"), any(), any(), any(Class.class), anyMap()))
                .thenReturn(new ResponseEntity<>("true", HttpStatus.OK));

        assertDoesNotThrow(() -> client.deleteSession(SESSION_ID));
    }

    @Test
    void deleteSessionThrowsWhenConsulReturnsUnexpectedBody() {
        when(restTemplate.exchange(contains("destroy"), any(), any(), any(Class.class), anyMap()))
                .thenReturn(new ResponseEntity<>("unexpected", HttpStatus.OK));

        assertThrows(RuntimeException.class, () -> client.deleteSession(SESSION_ID));
    }

    @Test
    void deleteSessionThrowsWhenConsulReturnsNonOkStatus() {
        when(restTemplate.exchange(contains("destroy"), any(), any(), any(Class.class), anyMap()))
                .thenReturn(new ResponseEntity<>("true", HttpStatus.INTERNAL_SERVER_ERROR));

        assertThrows(RuntimeException.class, () -> client.deleteSession(SESSION_ID));
    }
}

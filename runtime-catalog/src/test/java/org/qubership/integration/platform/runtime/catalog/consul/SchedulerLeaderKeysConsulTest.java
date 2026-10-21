package org.qubership.integration.platform.runtime.catalog.consul;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.runtime.catalog.consul.exception.KVNotFoundException;
import org.qubership.integration.platform.runtime.catalog.model.consul.KeyResponse;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SchedulerLeaderKeysConsulTest {

    private static final String KEY = "/config/qip-e2e/cip-engine-configurations/scheduler-leaders/orders-v1";

    @Test
    void shouldDeleteAKeyOnlyAtTheModifyIndexItWasReadAt() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        ConsulClient client = new ConsulClient(restTemplate, "http://consul:8500/");
        when(restTemplate.exchange(eq("http://consul:8500/v1/kv" + KEY + "?cas={cas}"), eq(HttpMethod.DELETE),
                any(HttpEntity.class), eq(String.class), eq(Map.of("cas", 42L))))
                .thenReturn(ResponseEntity.ok("true"), ResponseEntity.ok("false"));

        assertTrue(client.deleteKeyIfUnchanged(KEY, 42));
        assertFalse(client.deleteKeyIfUnchanged(KEY, 42));
    }

    @Test
    void shouldListTheSchedulerLeaderKeysAndDeleteOneByItsPath() throws Exception {
        ConsulClient client = mock(ConsulClient.class);
        ConsulService service = consulService(client);
        KeyResponse key = KeyResponse.builder().key(KEY.substring(1)).modifyIndex(42).build();
        when(client.getKV("/config/qip-e2e/cip-engine-configurations/scheduler-leaders/", true)).thenReturn(List.of(key));
        when(client.deleteKeyIfUnchanged(KEY, 42)).thenReturn(true);

        assertEquals(List.of(key), service.getSchedulerLeaderKeys());
        assertTrue(service.deleteSchedulerLeaderKey(key));
        verify(client).deleteKeyIfUnchanged(KEY, 42);
    }

    @Test
    void shouldListNoSchedulerLeaderKeysWhenConsulHasNone() throws Exception {
        ConsulClient client = mock(ConsulClient.class);
        when(client.getKV(any(), eq(true))).thenThrow(new KVNotFoundException("KV not present in consul"));

        assertEquals(List.of(), consulService(client).getSchedulerLeaderKeys());
    }

    private static ConsulService consulService(ConsulClient client) {
        ConsulService service = new ConsulService(new ObjectMapper(), client);
        ReflectionTestUtils.setField(service, "keyPrefix", "/config/qip-e2e");
        ReflectionTestUtils.setField(service, "keyEngineConfigRoot", "/cip-engine-configurations");
        ReflectionTestUtils.setField(service, "keySchedulerLeaders", "/scheduler-leaders");
        return service;
    }
}

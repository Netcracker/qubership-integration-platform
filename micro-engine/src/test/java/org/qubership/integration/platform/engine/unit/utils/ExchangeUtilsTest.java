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

package org.qubership.integration.platform.engine.unit.utils;

import com.fasterxml.jackson.core.JsonProcessingException;
import jakarta.ws.rs.core.MediaType;
import org.apache.camel.Exchange;
import org.apache.camel.ExchangeExtension;
import org.apache.camel.Message;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.qubership.integration.platform.engine.model.SessionElementProperty;
import org.qubership.integration.platform.engine.model.constants.CamelConstants;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.Properties;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.qubership.integration.platform.engine.util.ExchangeUtils;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static jakarta.ws.rs.core.HttpHeaders.CONTENT_TYPE;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.qubership.integration.platform.engine.model.constants.CamelConstants.INTERNAL_PROPERTY_PREFIX;

@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class ExchangeUtilsTest {

    @Test
    void shouldReturnTrueWhenKeyIsVariablesPropertyMapName() {
        Assertions.assertTrue(ExchangeUtils.isCommonOrSystemVariableMap(Properties.VARIABLES_PROPERTY_MAP_NAME));
    }

    @Test
    void shouldReturnFalseWhenKeyIsNotVariablesPropertyMapName() {
        assertFalse(ExchangeUtils.isCommonOrSystemVariableMap("somethingElse"));
    }

    @Test
    void shouldInterruptAndPrepareMessageWhenInterruptWithoutException() {
        Exchange exchange = mock(Exchange.class);
        ExchangeExtension ext = mock(ExchangeExtension.class);
        Message msg = mock(Message.class);

        when(exchange.getExchangeExtension()).thenReturn(ext);
        when(exchange.getMessage()).thenReturn(msg);

        ExchangeUtils.interruptExchange(exchange, 503);

        verify(ext).setInterrupted(true);
        verify(msg).setBody("");
        verify(msg).removeHeaders("*");
        verify(msg).setHeader(Exchange.HTTP_RESPONSE_CODE, 503);
    }

    @Test
    void shouldInterruptAndPutExceptionMessageWhenInterruptWithException() {
        Exchange exchange = mock(Exchange.class);
        ExchangeExtension ext = mock(ExchangeExtension.class);
        Message msg = mock(Message.class);

        when(exchange.getExchangeExtension()).thenReturn(ext);
        when(exchange.getMessage()).thenReturn(msg);

        Exception e = new IllegalStateException("boom");
        ExchangeUtils.interruptExchange(exchange, 400, e);

        verify(ext).setInterrupted(true);
        verify(msg).setBody("boom");
        verify(msg).removeHeaders("*");
        verify(msg).setHeader(Exchange.HTTP_RESPONSE_CODE, 400);
    }

    static class DummyPojo {
        int x = 1;
        String y = "a";
    }

    @Test
    void shouldFilterOutVariablesMapAndInternalPropertiesAndSerializeTheRest() {
        Exchange exchange = mock(Exchange.class);

        Map<String, Object> props = new LinkedHashMap<>();
        props.put(Properties.VARIABLES_PROPERTY_MAP_NAME, Map.of("myVariable", "myVariableValue"));
        props.put(INTERNAL_PROPERTY_PREFIX.concat("camelInnerProperty"), "innerValue");
        props.put("stringProperty", "hello");
        props.put("listProperty", List.of(1, 2));
        LinkedHashMap<String, Integer> mapVal = new LinkedHashMap<>();
        mapVal.put("a", 10);
        props.put("mapProperty", mapVal);
        DummyPojo pojo = new DummyPojo();
        props.put("pojoProperty", pojo);

        when(exchange.getProperties()).thenReturn(props);

        try (MockedStatic<CamelConstants> mockedCamel = Mockito.mockStatic(CamelConstants.class);
             MockedStatic<org.qubership.integration.platform.engine.service.debugger.util.json.JsonSerializationHelper> mockedJson =
                     Mockito.mockStatic(org.qubership.integration.platform.engine.service.debugger.util.json.JsonSerializationHelper.class)) {

            mockedCamel.when(() -> CamelConstants.isInternalProperty(INTERNAL_PROPERTY_PREFIX.concat("camelInnerProperty"))).thenReturn(true);
            mockedCamel.when(() -> CamelConstants.isInternalProperty(anyString())).thenReturn(false);

            mockedJson.when(() ->
                            org.qubership.integration.platform.engine.service.debugger.util.json.JsonSerializationHelper.serializeJson(pojo))
                    .thenReturn("{\"x\":1,\"y\":\"a\"}");

            Map<String, SessionElementProperty> out = ExchangeUtils.prepareExchangePropertiesForLogging(exchange);

            assertFalse(out.containsKey(Properties.VARIABLES_PROPERTY_MAP_NAME));

            assertTrue(out.containsKey(INTERNAL_PROPERTY_PREFIX.concat("camelInnerProperty")));

            SessionElementProperty spString = out.get("stringProperty");
            assertNotNull(spString);
            assertEquals(String.class.getName(), spString.getType());
            assertEquals("hello", spString.getValue());

            SessionElementProperty spList = out.get("listProperty");
            assertNotNull(spList);
            assertEquals(List.of(1, 2).getClass().getName(), spList.getType());
            assertEquals("[1, 2]", spList.getValue());

            SessionElementProperty spMap = out.get("mapProperty");
            assertNotNull(spMap);
            assertEquals(LinkedHashMap.class.getName(), spMap.getType());
            assertEquals("{a=10}", spMap.getValue());

            SessionElementProperty spPojo = out.get("pojoProperty");
            assertNotNull(spPojo);
            assertEquals(DummyPojo.class.getName(), spPojo.getType());
            assertEquals("{\"x\":1,\"y\":\"a\"}", spPojo.getValue());
        }
    }

    @Test
    void shouldReturnNullPropertyWhenSerializationThrowsJsonProcessingException() throws Exception {
        Exchange exchange = mock(Exchange.class);
        DummyPojo pojo = new DummyPojo();

        Map<String, Object> props = new HashMap<>();
        props.put("pojoProperty", pojo);
        when(exchange.getProperties()).thenReturn(props);

        try (MockedStatic<CamelConstants> mockedCamel = Mockito.mockStatic(CamelConstants.class);
             MockedStatic<org.qubership.integration.platform.engine.service.debugger.util.json.JsonSerializationHelper> mockedJson =
                     Mockito.mockStatic(org.qubership.integration.platform.engine.service.debugger.util.json.JsonSerializationHelper.class)) {

            mockedCamel.when(() -> CamelConstants.isInternalProperty(anyString())).thenReturn(false);

            mockedJson.when(() ->
                            org.qubership.integration.platform.engine.service.debugger.util.json.JsonSerializationHelper.serializeJson(pojo))
                    .thenThrow(new JsonProcessingException("fail") {
                    });

            Map<String, SessionElementProperty> out = ExchangeUtils.prepareExchangePropertiesForLogging(exchange);

            assertSame(SessionElementProperty.NULL_PROPERTY, out.get("pojoProperty"));
        }
    }

    @Test
    void shouldSetContentTypeWhenMissing() {
        Exchange exchange = mock(Exchange.class);
        Message message = mock(Message.class);
        Map<String, Object> headers = new HashMap<>();

        when(exchange.getMessage()).thenReturn(message);
        when(message.getHeaders()).thenReturn(headers);

        ExchangeUtils.setContentTypeIfMissing(exchange);

        assertEquals(MediaType.APPLICATION_JSON, headers.get(CONTENT_TYPE));
        verify(message, never()).setHeader(anyString(), any());
    }

    @Test
    void shouldNotOverrideExistingContentType() {
        Exchange exchange = mock(Exchange.class);
        Message message = mock(Message.class);
        Map<String, Object> headers = new HashMap<>();
        headers.put(CONTENT_TYPE, MediaType.TEXT_PLAIN);

        when(exchange.getMessage()).thenReturn(message);
        when(message.getHeaders()).thenReturn(headers);

        ExchangeUtils.setContentTypeIfMissing(exchange);

        assertEquals(MediaType.TEXT_PLAIN, headers.get(CONTENT_TYPE));
    }

    @Test
    void shouldFilterByPredicateAndDropNullValues() {
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("x.a", 1);
        in.put("x.b", null);
        in.put("y.c", 3);

        Predicate<Map.Entry<String, Object>> pred = e -> e.getKey().startsWith("x");

        Map<String, Object> out = ExchangeUtils.filterExchangeMap(in, pred);

        assertEquals(1, out.size());
        assertEquals(1, out.get("x.a"));
        assertFalse(out.containsKey("x.b"));
        assertFalse(out.containsKey("y.c"));
    }
}

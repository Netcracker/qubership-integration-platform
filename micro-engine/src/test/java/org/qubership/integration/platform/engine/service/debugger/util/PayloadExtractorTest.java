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

package org.qubership.integration.platform.engine.service.debugger.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.inject.Instance;
import jakarta.ws.rs.NotSupportedException;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.support.http.HttpUtil;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.qubership.integration.platform.engine.camel.context.propagation.CamelExchangeContextPropagation;
import org.qubership.integration.platform.engine.errorhandling.LoggingMaskingException;
import org.qubership.integration.platform.engine.errorhandling.errorcode.ErrorCode;
import org.qubership.integration.platform.engine.model.SessionElementProperty;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.Headers;
import org.qubership.integration.platform.engine.service.debugger.masking.MaskingService;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.qubership.integration.platform.engine.testutils.MockExchanges;
import org.qubership.integration.platform.engine.util.ExchangeUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class PayloadExtractorTest {

    private PayloadExtractor extractor(
            MaskingService maskingService,
            ObjectMapper mapper,
            Instance<CamelExchangeContextPropagation> propagation
    ) {
        return new PayloadExtractor(maskingService, mapper, propagation);
    }

    private Instance<CamelExchangeContextPropagation> instanceWith(CamelExchangeContextPropagation propagation) {
        @SuppressWarnings("unchecked")
        Instance<CamelExchangeContextPropagation> inst = mock(Instance.class);
        when(inst.isAmbiguous()).thenReturn(false);
        when(inst.stream()).thenReturn(Stream.of(propagation));
        return inst;
    }

    private Instance<CamelExchangeContextPropagation> instanceEmpty() {
        @SuppressWarnings("unchecked")
        Instance<CamelExchangeContextPropagation> inst = mock(Instance.class);
        when(inst.isAmbiguous()).thenReturn(false);
        when(inst.stream()).thenReturn(Stream.empty());
        return inst;
    }

    private Map<String, Object> attachHeaders(Exchange ex) {
        Message msg = MockExchanges.getMessage(ex);
        Map<String, Object> headers = new HashMap<>();
        when(msg.getHeaders()).thenReturn(headers);
        return headers;
    }

    @Test
    void shouldExtractHeadersAsStringsWhenCalled() {
        Exchange ex = MockExchanges.withMessage();
        Map<String, Object> headers = attachHeaders(ex);
        headers.put("a", 1);
        headers.put("b", null);

        MaskingService masking = mock(MaskingService.class);
        PayloadExtractor pe = extractor(masking, mock(ObjectMapper.class), instanceEmpty());

        Map<String, String> out = pe.extractHeadersForLogging(ex, Set.of("a"), false);

        assertEquals("1", out.get("a"));
        assertEquals("", out.get("b"));
        verifyNoInteractions(masking);
    }

    @Test
    void shouldMaskHeadersWhenMaskingEnabled() {
        Exchange ex = MockExchanges.withMessage();
        Map<String, Object> headers = attachHeaders(ex);
        headers.put("password", "123");
        headers.put("user", "bob");

        MaskingService masking = mock(MaskingService.class);
        PayloadExtractor pe = extractor(masking, mock(ObjectMapper.class), instanceEmpty());

        Map<String, String> out = pe.extractHeadersForLogging(ex, Set.of("password"), true);

        assertEquals("123", out.get("password"));
        assertEquals("bob", out.get("user"));
        verify(masking).maskFields(anyMap(), eq(Set.of("password")));
    }

    @Test
    void shouldMaskBodyWhenMaskingEnabledAndContentTypePresent() throws Exception {
        Exchange ex = MockExchanges.withMessage();
        Map<String, Object> headers = attachHeaders(ex);
        headers.put(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON);

        MaskingService masking = mock(MaskingService.class);
        PayloadExtractor pe = extractor(masking, mock(ObjectMapper.class), instanceEmpty());

        try (MockedStatic<MessageHelper> mh = Mockito.mockStatic(MessageHelper.class)) {
            mh.when(() -> MessageHelper.extractBody(ex)).thenReturn("{\"password\":\"123\"}");
            when(masking.maskFields(anyString(), eq(Set.of("password")), eq(MediaType.APPLICATION_JSON_TYPE)))
                    .thenReturn("{\"password\":\"******\"}");

            String out = pe.extractBodyForLogging(ex, Set.of("password"), true);

            assertEquals("{\"password\":\"******\"}", out);
            verify(masking).maskFields("{\"password\":\"123\"}", Set.of("password"), MediaType.APPLICATION_JSON_TYPE);
        }
    }

    @Test
    void shouldReturnBodyAsIsWhenMaskingThrowsLoggingMaskingException() throws Exception {
        Exchange ex = MockExchanges.withMessage();
        Map<String, Object> headers = attachHeaders(ex);
        headers.put(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON);

        MaskingService masking = mock(MaskingService.class);
        PayloadExtractor pe = extractor(masking, mock(ObjectMapper.class), instanceEmpty());

        try (MockedStatic<MessageHelper> mh = Mockito.mockStatic(MessageHelper.class)) {
            mh.when(() -> MessageHelper.extractBody(ex)).thenReturn("{\"password\":\"123\"}");
            when(masking.maskFields(anyString(), anySet(), any(MediaType.class)))
                    .thenThrow(new LoggingMaskingException("x", new RuntimeException("y")));

            String out = pe.extractBodyForLogging(ex, Set.of("password"), true);

            assertEquals("{\"password\":\"123\"}", out);
        }
    }

    @Test
    void shouldReturnBodyAsIsWhenMaskingThrowsNotSupportedException() throws Exception {
        Exchange ex = MockExchanges.withMessage();
        Map<String, Object> headers = attachHeaders(ex);
        headers.put(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON);

        MaskingService masking = mock(MaskingService.class);
        PayloadExtractor pe = extractor(masking, mock(ObjectMapper.class), instanceEmpty());

        try (MockedStatic<MessageHelper> mh = Mockito.mockStatic(MessageHelper.class)) {
            mh.when(() -> MessageHelper.extractBody(ex)).thenReturn("{\"password\":\"123\"}");
            when(masking.maskFields(anyString(), anySet(), any(MediaType.class)))
                    .thenThrow(new NotSupportedException("nope"));

            String out = pe.extractBodyForLogging(ex, Set.of("password"), true);

            assertEquals("{\"password\":\"123\"}", out);
        }
    }

    @Test
    void shouldReturnBodyAsIsWhenContentTypeMissing() throws Exception {
        Exchange ex = MockExchanges.withMessage();
        attachHeaders(ex);

        MaskingService masking = mock(MaskingService.class);
        PayloadExtractor pe = extractor(masking, mock(ObjectMapper.class), instanceEmpty());

        try (MockedStatic<MessageHelper> mh = Mockito.mockStatic(MessageHelper.class)) {
            mh.when(() -> MessageHelper.extractBody(ex)).thenReturn("{\"password\":\"123\"}");

            String out = pe.extractBodyForLogging(ex, Set.of("password"), true);

            assertEquals("{\"password\":\"123\"}", out);
            verifyNoInteractions(masking);
        }
    }

    @Test
    void shouldMaskExchangePropertiesWhenMaskingEnabled() throws Exception {
        Exchange ex = MockExchanges.withMessage();
        attachHeaders(ex);

        MaskingService masking = mock(MaskingService.class);
        PayloadExtractor pe = extractor(masking, mock(ObjectMapper.class), instanceEmpty());

        SessionElementProperty secretProp = mock(SessionElementProperty.class);
        SessionElementProperty keepProp = mock(SessionElementProperty.class);

        when(secretProp.getValue()).thenReturn("{\"secret\":\"1\"}");
        when(keepProp.getValue()).thenReturn("{\"keep\":\"2\"}");

        Map<String, SessionElementProperty> props = new HashMap<>();
        props.put("secret", secretProp);
        props.put("keep", keepProp);

        when(masking.maskJSON(eq("{\"secret\":\"1\"}"), eq(Set.of("secret")))).thenReturn("{\"secret\":\"******\"}");
        when(masking.maskJSON(eq("{\"keep\":\"2\"}"), eq(Set.of("secret")))).thenReturn("{\"keep\":\"2\"}");

        try (MockedStatic<ExchangeUtils> eu = Mockito.mockStatic(ExchangeUtils.class)) {
            eu.when(() -> ExchangeUtils.prepareExchangePropertiesForLogging(ex)).thenReturn(props);

            Map<String, SessionElementProperty> out = pe.extractExchangePropertiesForLogging(ex, Set.of("secret"), true);

            assertSame(props, out);

            verify(masking).maskPropertiesFields(props, Set.of("secret"));
            verify(secretProp).setValue(anyString());
            verify(keepProp).setValue(anyString());

            verify(secretProp).setValue("{\"secret\":\"******\"}");
            verify(keepProp).setValue("{\"keep\":\"2\"}");
        }
    }

    @Test
    void shouldSkipMaskingExchangePropertiesWhenMaskingDisabled() {
        Exchange ex = MockExchanges.withMessage();
        attachHeaders(ex);

        MaskingService masking = mock(MaskingService.class);
        PayloadExtractor pe = extractor(masking, mock(ObjectMapper.class), instanceEmpty());

        Map<String, SessionElementProperty> props = new HashMap<>();
        props.put("a", mock(SessionElementProperty.class));

        try (MockedStatic<ExchangeUtils> eu = Mockito.mockStatic(ExchangeUtils.class)) {
            eu.when(() -> ExchangeUtils.prepareExchangePropertiesForLogging(ex)).thenReturn(props);

            Map<String, SessionElementProperty> out = pe.extractExchangePropertiesForLogging(ex, Set.of("x"), false);

            assertSame(props, out);
            verifyNoInteractions(masking);
        }
    }

    @Test
    void shouldIgnoreInvalidJsonInPropertyWhenMaskJsonThrows() throws Exception {
        Exchange ex = MockExchanges.withMessage();
        attachHeaders(ex);

        MaskingService masking = mock(MaskingService.class);
        PayloadExtractor pe = extractor(masking, mock(ObjectMapper.class), instanceEmpty());

        SessionElementProperty p1 = mock(SessionElementProperty.class);
        when(p1.getValue()).thenReturn("bad-json");

        Map<String, SessionElementProperty> props = new HashMap<>();
        props.put("a", p1);

        try (MockedStatic<ExchangeUtils> eu = Mockito.mockStatic(ExchangeUtils.class)) {
            eu.when(() -> ExchangeUtils.prepareExchangePropertiesForLogging(ex)).thenReturn(props);
            when(masking.maskJSON(eq("bad-json"), eq(Set.of("secret")))).thenThrow(new JsonProcessingException("x") {});

            Map<String, SessionElementProperty> out = pe.extractExchangePropertiesForLogging(ex, Set.of("secret"), true);

            assertSame(props, out);
            verify(masking).maskPropertiesFields(props, Set.of("secret"));
            verify(masking).maskJSON("bad-json", Set.of("secret"));
            verify(p1, never()).setValue(anyString());
        }
    }

    @Test
    void shouldExtractContextWhenPropagationPresent() {
        MaskingService masking = mock(MaskingService.class);
        ObjectMapper mapper = mock(ObjectMapper.class);

        CamelExchangeContextPropagation propagation = mock(CamelExchangeContextPropagation.class);
        Map<String, String> ctx = new HashMap<>();
        ctx.put("token", "abc");
        when(propagation.buildContextSnapshotForSessions()).thenReturn(ctx);

        PayloadExtractor pe = extractor(masking, mapper, instanceWith(propagation));

        Map<String, String> out = pe.extractContextForLogging(Set.of("token"), true);

        assertEquals("abc", out.get("token"));
        verify(masking).maskFields(ctx, Set.of("token"));
    }

    @Test
    void shouldReturnEmptyContextWhenPropagationAbsent() {
        MaskingService masking = mock(MaskingService.class);
        PayloadExtractor pe = extractor(masking, mock(ObjectMapper.class), instanceEmpty());

        Map<String, String> out = pe.extractContextForLogging(Set.of("token"), true);

        assertTrue(out.isEmpty());
    }

    @Test
    void shouldConvertToJsonWhenMapperWorks() throws Exception {
        ObjectMapper mapper = mock(ObjectMapper.class);
        PayloadExtractor pe = extractor(mock(MaskingService.class), mapper, instanceEmpty());

        Map<String, Object> map = new HashMap<>();
        map.put("a", 1);

        when(mapper.writeValueAsString(map)).thenReturn("{\"a\":1}");

        assertEquals("{\"a\":1}", pe.convertToJson(map));
    }

    @Test
    void shouldReturnNullWhenConvertToJsonFails() throws Exception {
        ObjectMapper mapper = mock(ObjectMapper.class);
        PayloadExtractor pe = extractor(mock(MaskingService.class), mapper, instanceEmpty());

        Map<String, Object> map = new HashMap<>();
        map.put("a", 1);

        when(mapper.writeValueAsString(map)).thenThrow(new JsonProcessingException("x") {});

        assertNull(pe.convertToJson(map));
    }

    @Test
    void shouldReturnNullWhenConvertToJsonInputNull() {
        PayloadExtractor pe = extractor(mock(MaskingService.class), mock(ObjectMapper.class), instanceEmpty());
        assertNull(pe.convertToJson(null));
    }

    @Test
    void shouldGetResponseCodeWhenHeaderPresent() {
        Map<String, Object> headers = new HashMap<>();
        headers.put(Headers.CAMEL_HTTP_RESPONSE_CODE, 201);
        assertEquals(201, PayloadExtractor.getResponseCode(headers));
    }

    @Test
    void shouldReturnNullWhenResponseCodeHeaderAbsent() {
        assertNull(PayloadExtractor.getResponseCode(new HashMap<>()));
    }

    @Test
    void shouldReturnErrorCodeHttpWhenExceptionPresent() {
        Exchange ex = MockExchanges.withMessage();
        attachHeaders(ex);

        try (MockedStatic<ErrorCode> ec = Mockito.mockStatic(ErrorCode.class)) {
            ec.when(() -> ErrorCode.match(any(Exception.class))).thenReturn(ErrorCode.UNEXPECTED_BUSINESS_ERROR);

            int code = PayloadExtractor.getServletResponseCode(ex, new RuntimeException("x"));

            assertEquals(ErrorCode.UNEXPECTED_BUSINESS_ERROR.getHttpErrorCode(), code);
        }
    }

    @Test
    void shouldUseHttpUtilWhenExceptionNull() {
        Exchange ex = MockExchanges.withMessage();
        attachHeaders(ex);

        Message msg = MockExchanges.getMessage(ex);
        when(msg.getBody()).thenReturn("body");

        try (MockedStatic<HttpUtil> hu = Mockito.mockStatic(HttpUtil.class)) {
            hu.when(() -> HttpUtil.determineResponseCode(eq(ex), eq("body"))).thenReturn(202);

            int code = PayloadExtractor.getServletResponseCode(ex, null);

            assertEquals(202, code);
            hu.verify(() -> HttpUtil.determineResponseCode(eq(ex), eq("body")));
        }
    }

    @Test
    void shouldExtractContentTypeWhenHeaderPresent() {
        Exchange ex = MockExchanges.withMessage();
        Map<String, Object> headers = attachHeaders(ex);
        headers.put(HttpHeaders.CONTENT_TYPE, "application/json");

        MediaType ct = PayloadExtractor.extractContentType(ex);

        assertNotNull(ct);
        assertTrue(MediaType.APPLICATION_JSON_TYPE.isCompatible(ct));
    }

    @Test
    void shouldReturnNullWhenContentTypeMissing() {
        Exchange ex = MockExchanges.withMessage();
        attachHeaders(ex);
        assertNull(PayloadExtractor.extractContentType(ex));
    }
}

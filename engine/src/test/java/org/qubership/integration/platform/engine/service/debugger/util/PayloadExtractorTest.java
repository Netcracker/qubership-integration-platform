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

import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.util.InvalidMimeTypeException;
import org.springframework.util.MimeType;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PayloadExtractorTest {

    @ParameterizedTest
    @ValueSource(strings = {"application/json", "text/plain", "text/html", "application/xml"})
    void shouldReturnMimeTypeWhenContentTypeHeaderIsValidString(String contentTypeValue) {
        Exchange exchange = createExchangeWithContentType(contentTypeValue);

        MimeType result = PayloadExtractor.extractContentType(exchange);

        assertNotNull(result);
        assertEquals(MimeType.valueOf(contentTypeValue), result);
    }

    @Test
    void shouldReturnMimeTypeWithParametersWhenContentTypeContainsCharset() {
        String contentTypeValue = "text/html;charset=UTF-8";
        Exchange exchange = createExchangeWithContentType(contentTypeValue);

        MimeType result = PayloadExtractor.extractContentType(exchange);

        assertNotNull(result);
        assertEquals("text", result.getType());
        assertEquals("html", result.getSubtype());
        assertEquals("UTF-8", result.getParameter("charset"));
    }

    @Test
    void shouldReturnMimeTypeWhenContentTypeHeaderIsCollectionWithValidValue() {
        List<String> contentTypeCollection = List.of("application/json", "text/plain");
        Exchange exchange = createExchangeWithContentType(contentTypeCollection);

        MimeType result = PayloadExtractor.extractContentType(exchange);

        assertNotNull(result);
        assertEquals(MimeType.valueOf("application/json"), result);
    }

    @Test
    void shouldReturnNullWhenContentTypeHeaderIsAbsent() {
        Exchange exchange = createExchangeWithContentType(null);

        MimeType result = PayloadExtractor.extractContentType(exchange);

        assertNull(result);
    }

    @Test
    void shouldReturnNullWhenContentTypeHeaderIsEmptyCollection() {
        Exchange exchange = createExchangeWithContentType(Collections.emptyList());

        MimeType result = PayloadExtractor.extractContentType(exchange);

        assertNull(result);
    }

    @Test
    void shouldThrowExceptionWhenContentTypeHeaderIsInvalidMimeType() {
        Exchange exchange = createExchangeWithContentType("not-a-valid-mime-type");

        assertThrows(InvalidMimeTypeException.class, () -> PayloadExtractor.extractContentType(exchange));
    }

    @Test
    void shouldThrowExceptionWhenContentTypeHeaderIsEmptyString() {
        Exchange exchange = createExchangeWithContentType("");

        assertThrows(InvalidMimeTypeException.class, () -> PayloadExtractor.extractContentType(exchange));
    }

    @Test
    void shouldReturnFirstElementWhenContentTypeHeaderIsSingleElementCollection() {
        List<String> contentTypeCollection = List.of("text/xml");
        Exchange exchange = createExchangeWithContentType(contentTypeCollection);

        MimeType result = PayloadExtractor.extractContentType(exchange);

        assertNotNull(result);
        assertEquals(MimeType.valueOf("text/xml"), result);
    }

    @Test
    void shouldThrowExceptionWhenContentTypeHeaderIsNonStringObject() {
        Exchange exchange = createExchangeWithContentType(12345);

        assertThrows(InvalidMimeTypeException.class, () -> PayloadExtractor.extractContentType(exchange));
    }

    private Exchange createExchangeWithContentType(Object contentTypeValue) {
        Exchange exchange = mock(Exchange.class);
        Message message = mock(Message.class);
        Map<String, Object> headers = new HashMap<>();
        if (contentTypeValue != null) {
            headers.put(HttpHeaders.CONTENT_TYPE, contentTypeValue);
        }
        when(message.getHeaders()).thenReturn(headers);
        when(exchange.getMessage()).thenReturn(message);
        return exchange;
    }
}

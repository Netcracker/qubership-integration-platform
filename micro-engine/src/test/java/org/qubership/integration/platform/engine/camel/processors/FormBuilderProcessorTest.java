package org.qubership.integration.platform.engine.camel.processors;

import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import org.apache.camel.Exchange;
import org.apache.camel.Expression;
import org.apache.camel.language.simple.SimpleLanguage;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.forms.FormData;
import org.qubership.integration.platform.engine.forms.FormEntry;
import org.qubership.integration.platform.engine.model.constants.CamelConstants;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.qubership.integration.platform.engine.testutils.MockExchanges;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class FormBuilderProcessorTest {

    private final FormBuilderProcessor processor = new FormBuilderProcessor();

    @Mock
    SimpleLanguage simpleLanguage;
    @Mock
    Exchange exchange;
    @Mock
    FormData formData;
    @Mock
    FormEntry entry;

    @BeforeEach
    void setUp() throws Exception {
        exchange = MockExchanges.defaultExchange();
        setField(processor, "simpleLanguage", simpleLanguage);
    }

    @Test
    void shouldDoNothingWhenBodyMimeTypeBlank() throws Exception {
        exchange.setProperty(CamelConstants.Properties.BODY_MIME_TYPE, "   ");
        exchange.setProperty(CamelConstants.Properties.BODY_FORM_DATA, formData);

        processor.process(exchange);

        assertNull(exchange.getMessage().getBody());
        assertNull(exchange.getMessage().getHeader(HttpHeaders.CONTENT_TYPE));
        verifyNoInteractions(simpleLanguage);
    }

    @Test
    void shouldDoNothingWhenFormDataNull() throws Exception {
        exchange.setProperty(
                CamelConstants.Properties.BODY_MIME_TYPE,
                MediaType.APPLICATION_FORM_URLENCODED
        );

        processor.process(exchange);

        assertNull(exchange.getMessage().getBody());
        assertNull(exchange.getMessage().getHeader(HttpHeaders.CONTENT_TYPE));
        verifyNoInteractions(simpleLanguage);
    }

    @Test
    void shouldThrowExceptionWhenFormContentTypeUnsupported() {
        exchange.setProperty(CamelConstants.Properties.BODY_MIME_TYPE, MediaType.APPLICATION_JSON);
        exchange.setProperty(CamelConstants.Properties.BODY_FORM_DATA, formData);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> processor.process(exchange)
        );

        assertEquals(
                "Unsupported form content type: application/json",
                exception.getMessage()
        );
    }

    @Test
    void shouldBuildUrlEncodedFormWhenMimeTypeApplicationFormUrlEncoded() throws Exception {
        Expression expression = mock(Expression.class);

        exchange.setProperty(
                CamelConstants.Properties.BODY_MIME_TYPE,
                MediaType.APPLICATION_FORM_URLENCODED
        );
        exchange.setProperty(CamelConstants.Properties.BODY_FORM_DATA, formData);

        when(formData.getEntries()).thenReturn(List.of(entry));
        when(entry.getName()).thenReturn("customerId");
        when(entry.getValue()).thenReturn("${exchangeProperty.customerId}");

        when(simpleLanguage.createExpression("${exchangeProperty.customerId}")).thenReturn(expression);
        when(expression.evaluate(exchange, Object.class)).thenReturn("C-100500");

        processor.process(exchange);

        assertArrayEquals(
                "customerId=C-100500".getBytes(StandardCharsets.UTF_8),
                exchange.getMessage().getBody(byte[].class)
        );
        assertEquals(
                MediaType.APPLICATION_FORM_URLENCODED_TYPE,
                exchange.getMessage().getHeader(HttpHeaders.CONTENT_TYPE)
        );
    }

    @Test
    void shouldBuildMultipartFormWhenMimeTypeMultipartFormData() throws Exception {
        Expression fileNameExpression = mock(Expression.class);
        Expression valueExpression = mock(Expression.class);

        exchange.setProperty(
                CamelConstants.Properties.BODY_MIME_TYPE,
                MediaType.MULTIPART_FORM_DATA
        );
        exchange.setProperty(CamelConstants.Properties.BODY_FORM_DATA, formData);

        when(formData.getEntries()).thenReturn(List.of(entry));
        when(entry.getName()).thenReturn("attachment");
        when(entry.getFileName()).thenReturn("${exchangeProperty.fileName}");
        when(entry.getValue()).thenReturn("${exchangeProperty.payload}");
        when(entry.getMimeType()).thenReturn(MediaType.TEXT_PLAIN_TYPE);

        when(simpleLanguage.createExpression("${exchangeProperty.fileName}")).thenReturn(fileNameExpression);
        when(simpleLanguage.createExpression("${exchangeProperty.payload}")).thenReturn(valueExpression);
        when(fileNameExpression.evaluate(exchange, Object.class)).thenReturn("report.txt");
        when(valueExpression.evaluate(exchange, Object.class)).thenReturn("hello world");

        processor.process(exchange);

        byte[] body = exchange.getMessage().getBody(byte[].class);
        String multipartBody = new String(body, StandardCharsets.UTF_8);
        String contentType = exchange.getMessage().getHeader(HttpHeaders.CONTENT_TYPE, String.class);

        Assertions.assertTrue(body.length > 0);
        Assertions.assertTrue(contentType.startsWith("multipart/form-data"));
        Assertions.assertTrue(multipartBody.contains("hello world"));
        Assertions.assertTrue(multipartBody.contains("report.txt"));
        Assertions.assertTrue(multipartBody.contains("attachment"));
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}

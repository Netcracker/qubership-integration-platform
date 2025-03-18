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

package org.qubership.integration.platform.engine.camel.processors;

import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.apache.camel.Expression;
import org.apache.camel.Message;
import org.apache.camel.Processor;
import org.apache.camel.language.simple.SimpleLanguage;
import org.apache.logging.log4j.util.Strings;
import org.qubership.integration.platform.engine.forms.FormData;
import org.qubership.integration.platform.engine.forms.FormEntry;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.Properties;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;

import static java.util.Objects.isNull;

@Component
@Slf4j
public class FormBuilderProcessor implements Processor {

    private interface FormEntryBuilder {
        Object build(Exchange exchange, FormEntry entry) throws IOException;
    }

    @Override
    public void process(Exchange exchange) throws Exception {
        String bodyMimeType = exchange.getProperty(Properties.BODY_MIME_TYPE, String.class);
        if (Strings.isBlank(bodyMimeType)) {
            log.error("Body MIME type is blank.");
            return;
        }

        FormData formData = exchange.getProperty(Properties.BODY_FORM_DATA, FormData.class);
        if (isNull(formData)) {
            log.error("Form data is null.");
            return;
        }

        MediaType contentType = MediaType.valueOf(bodyMimeType);
        FormEntryBuilder formEntryBuilder = getFormEntryBuilder(contentType);
        MultiValueMap<String, Object> form = buildForm(exchange, formData, formEntryBuilder);
        writeForm(exchange, contentType, form);
    }

    private MultiValueMap<String, Object> buildForm(
            Exchange exchange,
            FormData formData,
            FormEntryBuilder entryBuilder
    ) throws IOException {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        for (FormEntry entry : formData.getEntries()) {
            Object formEntry = entryBuilder.build(exchange, entry);
            form.add(entry.getName(), formEntry);
        }
        return form;
    }

    private FormEntryBuilder getFormEntryBuilder(MediaType formType) {
        return isMultipart(formType) ? this::buildHttpEntity : this::buildGenericEntity;
    }

    private boolean isMultipart(MediaType formType) {
        return formType.getType().equalsIgnoreCase("multipart");
    }

    private Object buildGenericEntity(Exchange exchange, FormEntry entry) throws IOException {
        return evaluate(exchange, entry.getValue());
    }

    private HttpEntity<?> buildHttpEntity(Exchange exchange, FormEntry entry) throws IOException {
        HttpHeaders headers = new HttpHeaders();
        Object value = evaluate(exchange, entry.getValue());
        if (value instanceof HttpEntity) {
            headers.addAll(((HttpEntity<?>) value).getHeaders());
        }
        headers.setContentType(entry.getMimeType());
        String fileName = String.valueOf(evaluate(exchange, entry.getFileName()));
        if (Strings.isNotBlank(fileName)) {
            headers.setContentDispositionFormData(entry.getName(), fileName);
        }
        Object body = (value instanceof HttpEntity) ? ((HttpEntity<?>) value).getBody() : value;
        return new HttpEntity<>(body, headers);
    }

    private void writeForm(Exchange exchange, MediaType contentType, MultiValueMap<String, Object> form)
            throws IOException {
        FormHttpMessageConverter converter = new FormHttpMessageConverter();
        HttpHeaders headers = new HttpHeaders();
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        converter.write(form, contentType, new HttpOutputMessage() {
            @Override
            public OutputStream getBody() throws IOException {
                return outputStream;
            }

            @Override
            public HttpHeaders getHeaders() {
                return headers;
            }
        });
        Message message = exchange.getMessage();
        headers.forEach((name, values) -> values.forEach(value -> message.setHeader(name, value)));
        message.setBody(outputStream.toByteArray());
    }

    private Object evaluate(Exchange exchange, String expressionString) throws IOException {
        try (SimpleLanguage simpleLanguage = new SimpleLanguage()) {
            simpleLanguage.setCamelContext(exchange.getContext());
            Expression expression = simpleLanguage.createExpression(expressionString);
            return expression.evaluate(exchange, Object.class);
        }
    }
}

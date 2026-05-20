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

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.mail.BodyPart;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetHeaders;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMultipart;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.apache.camel.Expression;
import org.apache.camel.Processor;
import org.apache.camel.language.simple.SimpleLanguage;
import org.apache.commons.lang3.StringUtils;
import org.jboss.resteasy.reactive.common.providers.serialisers.MapAsFormUrlEncodedProvider;
import org.qubership.integration.platform.engine.forms.FormData;
import org.qubership.integration.platform.engine.forms.FormEntry;
import org.qubership.integration.platform.engine.model.constants.CamelConstants;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.function.BiConsumer;

import static java.util.Objects.isNull;

@ApplicationScoped
@Slf4j
@Named("formBuilderProcessor")
public class FormBuilderProcessor implements Processor {
    @Inject
    SimpleLanguage simpleLanguage;

    @Override
    public void process(Exchange exchange) throws Exception {
        String bodyMimeType = exchange.getProperty(CamelConstants.Properties.BODY_MIME_TYPE, String.class);
        if (StringUtils.isBlank(bodyMimeType)) {
            log.error("Body MIME type is blank.");
            return;
        }

        FormData formData = exchange.getProperty(CamelConstants.Properties.BODY_FORM_DATA, FormData.class);
        if (isNull(formData)) {
            log.error("Form data is null.");
            return;
        }

        MediaType contentType = MediaType.valueOf(bodyMimeType);
        BiConsumer<Exchange, FormData> handler = getFormHandler(contentType);
        handler.accept(exchange, formData);
    }

    private BiConsumer<Exchange, FormData> getFormHandler(MediaType contentType) {
        if (MediaType.MULTIPART_FORM_DATA_TYPE.equals(contentType)) {
            return this::handleMultipartForm;
        } else if (MediaType.APPLICATION_FORM_URLENCODED_TYPE.equals(contentType)) {
            return this::handleUrlEncodedForm;
        } else {
            throw new IllegalArgumentException("Unsupported form content type: " + contentType);
        }
    }

    private void handleMultipartForm(
            Exchange exchange,
            FormData formData
    ) {
        try {
            MimeMultipart multipart = new MimeMultipart(MediaType.MULTIPART_FORM_DATA_TYPE.getSubtype());
            for (FormEntry entry : formData.getEntries()) {
                BodyPart bodyPart = toBodyPart(exchange, entry);
                multipart.addBodyPart(bodyPart);
            }

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            multipart.writeTo(outputStream);

            exchange.getMessage().setHeader(HttpHeaders.CONTENT_TYPE, multipart.getContentType());
            exchange.getMessage().setBody(outputStream.toByteArray());
        } catch (MessagingException | IOException e) {
            throw new RuntimeException("Failed to build multipart form data", e);
        }
    }

    private BodyPart toBodyPart(Exchange exchange, FormEntry formEntry) throws MessagingException {
        String fileName = String.valueOf(evaluate(exchange, formEntry.getFileName()));

        Object value = evaluate(exchange, formEntry.getValue());
        byte[] data = exchange.getContext().getTypeConverter().convertTo(byte[].class, value);

        InternetHeaders headers = new InternetHeaders();
        headers.addHeader(HttpHeaders.CONTENT_TYPE, formEntry.getMimeType().toString());
        headers.addHeader(HttpHeaders.CONTENT_LENGTH, String.valueOf(data.length));

        var part = new MimeBodyPart(headers, data);
        part.setFileName(fileName);
        part.setDisposition(String.format("form_data; name=%s", formEntry.getName()));

        return part;
    }

    private void handleUrlEncodedForm(Exchange exchange, FormData formData) {
        try {
            MultivaluedMap<String, Object> map = new MultivaluedHashMap<>();
            formData.getEntries().forEach(entry -> {
                Object value = evaluate(exchange, entry.getValue());
                map.add(entry.getName(), value);
            });
            MapAsFormUrlEncodedProvider provider = new MapAsFormUrlEncodedProvider();

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            provider.writeTo(map, null, null, null,
                    MediaType.APPLICATION_FORM_URLENCODED_TYPE, null, outputStream);
            exchange.getMessage().setBody(outputStream.toByteArray());
            exchange.getMessage().setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_FORM_URLENCODED_TYPE);
        } catch (IOException e) {
            throw new RuntimeException("Failed to build urlencoded form data", e);
        }
    }

    private Object evaluate(Exchange exchange, String expressionString) {
        simpleLanguage.setCamelContext(exchange.getContext());
        Expression expression = simpleLanguage.createExpression(expressionString);
        return expression.evaluate(exchange, Object.class);
    }
}

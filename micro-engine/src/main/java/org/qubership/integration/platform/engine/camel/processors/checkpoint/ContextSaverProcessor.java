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

package org.qubership.integration.platform.engine.camel.processors.checkpoint;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.qubership.integration.platform.engine.camel.components.context.propagation.ContextOperationsWrapper;
import org.qubership.integration.platform.engine.model.constants.CamelConstants;
import org.qubership.integration.platform.engine.persistence.shared.entity.Checkpoint;
import org.qubership.integration.platform.engine.persistence.shared.entity.Property;
import org.qubership.integration.platform.engine.service.CheckpointSessionService;
import org.qubership.integration.platform.engine.service.debugger.util.MessageHelper;
import org.qubership.integration.platform.engine.util.ExchangeUtils;

import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

import groovy.lang.GroovyObject;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class ContextSaverProcessor implements Processor {

    private final CheckpointSessionService checkpointSessionService;
    private final ObjectMapper checkpointMapper;
    private final Optional<ContextOperationsWrapper> contextOperations;

    @Autowired
    public ContextSaverProcessor(
            CheckpointSessionService checkpointSessionService,
            @Qualifier("checkpointMapper") ObjectMapper checkpointMapper,
            Optional<ContextOperationsWrapper> contextOperations
    ) {
        this.checkpointSessionService = checkpointSessionService;
        this.checkpointMapper = checkpointMapper;
        this.contextOperations = contextOperations;
    }

    @Override
    public void process(Exchange exchange) throws Exception {
        try {
            String body = MessageHelper.extractBody(exchange);

            Checkpoint checkpoint = Checkpoint.builder()
                    .checkpointElementId(exchange.getProperty(
                        CamelConstants.Properties.CHECKPOINT_ELEMENT_ID, String.class))
                    .headers(checkpointMapper.writeValueAsString(
                            ExchangeUtils.filterExchangeMap(
                                    exchange.getMessage().getHeaders(),
                                    entry -> !CamelConstants.isInternalHeader(entry.getKey()))))
                    .body(body == null ? null : body.getBytes(StandardCharsets.UTF_8))
                    .properties(getPropertiesForSave(
                            ExchangeUtils.filterExchangeMap(
                                    exchange.getProperties(),
                                    entry -> !CamelConstants.isInternalProperty(entry.getKey())))
                    )
                    .build();

            // dump propagation and tracing context
            if (contextOperations.isPresent()) {
                checkpoint.setContextData(checkpointMapper.writeValueAsString(
                    contextOperations.get().getSerializableContextData()));
            }

            checkpointSessionService.saveAndAssignCheckpoint(
                    checkpoint,
                    exchange.getProperty(CamelConstants.Properties.SESSION_ID, String.class));
        } catch (Exception e) {
            log.error("Failed to create session checkpoint", e);
            throw new RuntimeException("Failed to create session checkpoint", e);
        }
    }

    List<Property> getPropertiesForSave(Map<String, Object> properties) {
        return properties.entrySet().stream()
                .map(entry -> Property.builder()
                        .name(entry.getKey())
                        .type(entry.getValue().getClass().getName())
                        .value(serializeProperty(entry.getValue().getClass(), entry.getValue()))
                        .build())
                .collect(Collectors.toList());
    }

    byte[] serializeProperty(Class<?> propertyClass, Object property) {
        if (Serializable.class.isAssignableFrom(propertyClass) && !GroovyObject.class.isAssignableFrom(propertyClass)) {
            try {
                return serializeWithIOLibrary(property);
            } catch (Exception e) {
                //when receive Serializable object, but it contains non-Serializable fields/objects inside;
                return serializeWithObjectMapper(property);
            }
        }
        return serializeWithObjectMapper(property);
    }

    byte[] serializeWithObjectMapper(Object property) {
        try {
            return checkpointMapper.writeValueAsBytes(property);
        } catch (Exception e) {
            log.error("Failed to create session checkpoint", e);
            throw new RuntimeException("Failed to create session checkpoint", e);
        }
    }

    // serialize properties with Serializable interface
    byte[] serializeWithIOLibrary(final Object obj) throws Exception {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             ObjectOutputStream out = new ObjectOutputStream(bos)) {
            out.writeObject(obj);
            out.flush();
            return bos.toByteArray();
        } catch (Exception ex) {
            throw new Exception(ex);
        }
    }
}

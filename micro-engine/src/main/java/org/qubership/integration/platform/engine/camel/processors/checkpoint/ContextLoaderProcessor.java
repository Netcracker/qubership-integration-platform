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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.qubership.integration.platform.engine.camel.components.context.propagation.ContextOperationsWrapper;
import org.qubership.integration.platform.engine.model.checkpoint.CheckpointPayloadOptions;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.Properties;
import org.qubership.integration.platform.engine.persistence.shared.entity.Checkpoint;
import org.qubership.integration.platform.engine.persistence.shared.entity.Property;
import org.qubership.integration.platform.engine.persistence.shared.entity.SessionInfo;
import org.qubership.integration.platform.engine.service.CheckpointSessionService;
import org.qubership.integration.platform.engine.service.debugger.util.MessageHelper;
import org.qubership.integration.platform.engine.util.CheckpointUtils;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import jakarta.persistence.EntityNotFoundException;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.Processor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class ContextLoaderProcessor implements Processor {
    private final CheckpointSessionService checkpointSessionService;
    private final ObjectMapper checkpointMapper;
    private final Optional<ContextOperationsWrapper> contextOperations;

    @Autowired
    public ContextLoaderProcessor(
            CheckpointSessionService checkpointSessionService,
            @Qualifier("checkpointMapper") ObjectMapper checkpointMapper,
            Optional<ContextOperationsWrapper> contextOperations
    ) {
        this.checkpointSessionService = checkpointSessionService;
        this.checkpointMapper = checkpointMapper;
        this.contextOperations = contextOperations;
    }

    @Override
    public void process(Exchange exchange) throws CheckpointException {
        try {
            CheckpointUtils.CheckpointInfo checkpointInfo = CheckpointUtils.extractTriggeredCheckpointInfo(exchange);

            Checkpoint checkpoint = checkpointInfo != null
                    ? checkpointSessionService.findCheckpoint(checkpointInfo.sessionId(), checkpointInfo.chainId(), checkpointInfo.checkpointElementId())
                    : null;

            if (checkpoint == null) {
                log.error("Can't find checkpoint with session id: {}, checkpoint id: {}", checkpointInfo.sessionId(),
                        checkpointInfo.checkpointElementId());
                throw new EntityNotFoundException(
                        "Can't find checkpoint with session id: " + checkpointInfo.sessionId() +
                                ", checkpoint id: " + checkpointInfo.checkpointElementId());
            }

            CheckpointPayloadOptions replaceOptions = parseReplaceOptions(exchange);
            restorePayloadFromCheckpoint(exchange, checkpoint);
            updatePayloadFromRequest(exchange, replaceOptions);

            String sessionId = exchange.getProperty(Properties.SESSION_ID, String.class);
            String parentSessionId = checkpoint.getSession().getId();
            String originalSessionId = checkpointSessionService.findOriginalSessionInfo(parentSessionId)
                    .map(SessionInfo::getId).orElse(parentSessionId);
            CheckpointUtils.setSessionProperties(exchange, parentSessionId, originalSessionId);
            checkpointSessionService.updateSessionParent(sessionId, parentSessionId);
        } catch (Exception e) {
            throw new CheckpointException("Failed to load session from checkpoint", e);
        }
    }

    private CheckpointPayloadOptions parseReplaceOptions(Exchange exchange) throws Exception {
        String body = MessageHelper.extractBody(exchange);
        try {
            return StringUtils.isNotEmpty(body) ?
                    checkpointMapper.readValue(body, CheckpointPayloadOptions.class) :
                    CheckpointPayloadOptions.EMPTY;
        } catch (Exception e) {
            log.error("Failed to parse checkpoint options from retry request", e);
            throw new RuntimeException("Failed to parse checkpoint options from retry request", e);
        }
    }

    private void restorePayloadFromCheckpoint(Exchange exchange, Checkpoint checkpoint) throws IOException {
        Message message = exchange.getMessage();

        // restore propagation and tracing contexts
        if (contextOperations.isPresent() && StringUtils.isNotEmpty(checkpoint.getContextData())) {
            Map<String, Map<String, Object>> contextData =
                    checkpointMapper.readValue(checkpoint.getContextData(), new TypeReference<>() {});
            contextOperations.get().activateWithSerializableContextData(contextData);
        }

        deserializeProperties(checkpoint, exchange.getProperties());
        message.getHeaders().putAll(
                checkpointMapper.readValue(checkpoint.getHeaders(), new TypeReference<Map<String, Object>>() {
                }));
        message.setBody(checkpoint.getBody() == null ? null : new String(checkpoint.getBody(), StandardCharsets.UTF_8));
    }

    private void updatePayloadFromRequest(Exchange exchange, CheckpointPayloadOptions replaceOptions) {
        exchange.getProperties().putAll(replaceOptions.getProperties());
        exchange.getMessage().getHeaders().putAll(replaceOptions.getHeaders());
        if (replaceOptions.getBody() != null) {
            exchange.getMessage().setBody(replaceOptions.getBody());
        }
    }

    void deserializeProperties(Checkpoint checkpoint, Map<String, Object> result) throws IOException {

        for (Property property : checkpoint.getProperties()) {
            try {
                Class<?> clazz = Class.forName(property.getType());
                if (Serializable.class.isAssignableFrom(clazz)) {
                    result.put(property.getName(), deserializeWithMetadata(property.getValue()));
                } else {
                    result.put(property.getName(), checkpointMapper.readValue(property.getValue(), clazz));
                }
            } catch (ClassNotFoundException e) {
                try {
                    result.put(property.getName(), checkpointMapper.readValue(property.getValue(), new TypeReference<>() {
                    }));
                } catch (Exception exception) {
                    //WA for properties without type after migration
                    result.put(property.getName(), new String(property.getValue()));
                }
            }
        }
    }

    // deserialize properties with Serializable interface
    static Object deserializeWithMetadata(byte[] bytes) {
        try (ByteArrayInputStream bis = new ByteArrayInputStream(bytes);
             ObjectInput in = new ObjectInputStream(bis)) {
            return in.readObject();
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }
}

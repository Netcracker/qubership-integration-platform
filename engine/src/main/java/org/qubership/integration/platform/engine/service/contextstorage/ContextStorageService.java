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

package org.qubership.integration.platform.engine.service.contextstorage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.engine.errorhandling.ContextStorageException;
import org.qubership.integration.platform.engine.persistence.shared.entity.ContextSystemRecords;
import org.qubership.integration.platform.engine.persistence.shared.repository.ContextStorageRespository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ContextStorageService {

    private static final String CONTEXT = "context";
    private final ContextStorageRespository contextStorageRepository;

    private final ObjectMapper objectMapper;

    @Autowired
    public ContextStorageService(
            ContextStorageRespository contextStorageRepository,
            ObjectMapper objectMapper
    ) {
        this.contextStorageRepository = contextStorageRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional("checkpointTransactionManager")
    public void storeValue(String contextKey, String contextValue, String contextServiceId, String contextId, long ttl) {
        // The value is built as a tree because the mapper drops a null map value, and a null has to reach the statement as a JSON null.
        ObjectNode context = objectMapper.createObjectNode().put(contextKey, contextValue);
        String value = objectMapper.createObjectNode().set(CONTEXT, context).toString();
        Instant now = Instant.now();
        contextStorageRepository.mergeValue(UUID.randomUUID().toString(), value, contextServiceId, contextId, Timestamp.from(now), Timestamp.from(now.plusSeconds(ttl)));
        log.debug("Value stored successfully for contextKey: {}, contextServiceId: {}, contextId: {}", contextKey, contextServiceId, contextId);
    }

    public Map<String, String> getValue(String contextServiceId, String contextId, List<String> keys) {
        Object jsonValue = contextStorageRepository.findByContextServiceIdAndContextId(contextServiceId, contextId)
                .filter(record -> record.getExpiresAt().after(Timestamp.from(Instant.now())))
                .map(ContextSystemRecords::getValue).orElse(null);
        if (jsonValue != null) {
            try {
                JsonNode jsonNode = objectMapper.readTree(jsonValue.toString());
                JsonNode contextNode = jsonNode.get(CONTEXT);
                return keys.stream().filter(contextNode::has).collect(Collectors.toMap(key -> key, key -> contextNode.get(key).asText()));
            } catch (JsonProcessingException e) {
                log.error("Error occurred while processing JSON for contextServiceId: {}, contextId: {}", contextServiceId, contextId, e);
                throw new RuntimeException(e);
            }
        }
        log.warn("Context keys: {}  with contextServiceId: {}, contextId: {} is either not present or expired", keys, contextServiceId, contextId);
        return Collections.emptyMap();
    }

    public void deleteValue(String contextServiceID, String contextId) {
        try {
            contextStorageRepository.deleteRecordByContextServiceIdAndContextId(contextServiceID, contextId);
            log.info("Value deleted successfully for contextServiceID: {}, contextId: {}", contextServiceID, contextId);
        } catch (Exception e) {
            throw new ContextStorageException("Error occurred while deleting value for contextServiceID: " + contextServiceID + " contextId: " + contextId, e);
        }
    }

    public void deleteOldRecords() {
        try {
            List<ContextSystemRecords> oldRecords  = contextStorageRepository.findAllByExpiresAtBefore(Timestamp.from(Instant.now()));
            if (oldRecords != null && !oldRecords.isEmpty()) {
                contextStorageRepository.deleteAll(oldRecords);
                log.debug("Deleted old records from context storage");
            } else {
                log.debug("No old records found to delete");
            }
        } catch (Exception e) {
            throw new ContextStorageException("Error occurred while deleting old records from context storage", e);
        }
    }

}

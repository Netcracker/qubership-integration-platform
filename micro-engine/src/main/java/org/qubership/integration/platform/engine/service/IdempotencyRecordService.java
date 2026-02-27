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

package org.qubership.integration.platform.engine.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.scheduler.Scheduled;
import io.smallrye.common.annotation.Identifier;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.engine.camel.idempotency.IdempotencyRecordData;
import org.qubership.integration.platform.engine.camel.idempotency.IdempotencyRecordStatus;
import org.qubership.integration.platform.engine.persistence.shared.repository.IdempotencyRecordRepository;

@Slf4j
@ApplicationScoped
public class IdempotencyRecordService {
    private final ObjectMapper objectMapper;
    private final IdempotencyRecordRepository idempotencyRecordRepository;

    @Inject
    public IdempotencyRecordService(
            @Identifier("jsonMapper") ObjectMapper objectMapper,
            IdempotencyRecordRepository idempotencyRecordRepository
    ) {
        this.objectMapper = objectMapper;
        this.idempotencyRecordRepository = idempotencyRecordRepository;
    }

    @Transactional
    public boolean insertIfNotExists(String key, int ttl) {
        String data = buildIdempotencyRecordData();
        return idempotencyRecordRepository
                .insertIfNotExistsOrUpdateIfExpired(key, data, ttl) > 0;
    }

    @Transactional
    public boolean exists(String key) {
        return idempotencyRecordRepository.existsByKeyAndNotExpired(key);
    }

    @Transactional
    public boolean delete(String key) {
        return idempotencyRecordRepository.deleteByKeyAndNotExpired(key) > 0;
    }

    @Scheduled(
            cron = "${qip.idempotency.expired-records-cleanup-cron:0 */5 * ? * *}",
            executeWith = Scheduled.SIMPLE
    )
    @Transactional
    public void deleteExpired() {
        log.debug("Deleting expired idempotency records.");
        idempotencyRecordRepository.deleteExpired();
    }

    private String buildIdempotencyRecordData() {
        try {
            IdempotencyRecordData data = IdempotencyRecordData.builder()
                    .status(IdempotencyRecordStatus.RECEIVED)
                    .build();
            return objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException ignored) {
            return null;
        }
    }
}

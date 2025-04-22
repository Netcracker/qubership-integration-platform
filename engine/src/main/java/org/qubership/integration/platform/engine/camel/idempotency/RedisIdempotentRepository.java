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

package org.qubership.integration.platform.engine.camel.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.apache.camel.component.redis.processor.idempotent.RedisStringIdempotentRepository;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

@Slf4j
public class RedisIdempotentRepository extends RedisStringIdempotentRepository {
    private final IdempotentRepositoryParameters keyParameters;
    private final ObjectMapper objectMapper;
    private final ValueOperations<String, String> valueOperations;

    public RedisIdempotentRepository(
        RedisTemplate<String, String> redisTemplate,
        ObjectMapper objectMapper,
        IdempotentRepositoryParameters keyParameters
    ) {
        super(redisTemplate, null);
        setExpiry(keyParameters.getTtl());
        this.objectMapper = objectMapper;
        this.keyParameters = keyParameters;
        this.valueOperations = redisTemplate.opsForValue();
    }

    @Override
    protected String createRedisKey(String idempotencyKey) {
        return keyParameters.getKeyStrategy().buildRepositoryKey(idempotencyKey);
    }

    @Override
    public boolean add(Exchange exchange, String key) {
        String redisKey = createRedisKey(key);
        String value = createRedisValue(exchange);
        if (keyParameters.getTtl() > 0) {
            Duration expiry = Duration.ofSeconds(keyParameters.getTtl());
            return valueOperations.setIfAbsent(redisKey, value, expiry);
        }
        return valueOperations.setIfAbsent(redisKey, value);
    }

    protected String createRedisValue(Exchange exchange) {
        IdempotencyRecord record = IdempotencyRecord.builder()
            .status(IdempotencyRecordStatus.RECEIVED)
            .createdAt(Timestamp.from(Instant.now()))
            .build();
        try {
            return objectMapper.writeValueAsString(record);
        } catch (JsonProcessingException exception) {
            log.error("Failed to create idempotency Redis value", exception);
            return null;
        }
    }

    @Override
    public void clear() {
        // We are not deleting keys on stop.
    }
}

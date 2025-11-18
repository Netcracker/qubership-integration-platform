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

    @Scheduled(cron = "${qip.idempotency.expired-records-cleanup-cron:0 */5 * ? * *}")
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

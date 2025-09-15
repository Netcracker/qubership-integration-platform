package org.qubership.integration.platform.engine.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.engine.camel.idempotency.IdempotencyRecordData;
import org.qubership.integration.platform.engine.camel.idempotency.IdempotencyRecordStatus;
import org.qubership.integration.platform.engine.persistence.shared.repository.IdempotencyRecordRepository;
import jakarta.inject.Inject;
import org.springframework.scheduling.annotation.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@ApplicationScoped
public class IdempotencyRecordService {
    private final ObjectMapper objectMapper;
    private final IdempotencyRecordRepository idempotencyRecordRepository;

    @Inject
    public IdempotencyRecordService(
            ObjectMapper objectMapper,
            IdempotencyRecordRepository idempotencyRecordRepository
    ) {
        this.objectMapper = objectMapper;
        this.idempotencyRecordRepository = idempotencyRecordRepository;
    }

    @Transactional("checkpointTransactionManager")
    public boolean insertIfNotExists(String key, int ttl) {
        String data = buildIdempotencyRecordData();
        return idempotencyRecordRepository
                .insertIfNotExistsOrUpdateIfExpired(key, data, ttl) > 0;
    }

    @Transactional("checkpointTransactionManager")
    public boolean exists(String key) {
        return idempotencyRecordRepository.existsByKeyAndNotExpired(key);
    }

    @Transactional("checkpointTransactionManager")
    public boolean delete(String key) {
        return idempotencyRecordRepository.deleteByKeyAndNotExpired(key) > 0;
    }

    @Scheduled(cron = "${qip.idempotency.expired-records-cleanup-cron:0 */5 * ? * *}")
    @Transactional("checkpointTransactionManager")
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

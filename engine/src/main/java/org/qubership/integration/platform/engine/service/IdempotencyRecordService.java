package org.qubership.integration.platform.engine.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.engine.camel.idempotency.IdempotencyRecordData;
import org.qubership.integration.platform.engine.camel.idempotency.IdempotencyRecordStatus;
import org.qubership.integration.platform.engine.persistence.shared.entity.IdempotencyRecord;
import org.qubership.integration.platform.engine.persistence.shared.repository.IdempotencyRecordRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class IdempotencyRecordService {
    private final ObjectMapper objectMapper;
    private final IdempotencyRecordRepository idempotencyRecordRepository;

    @Autowired
    public IdempotencyRecordService(
            ObjectMapper objectMapper,
            IdempotencyRecordRepository idempotencyRecordRepository
    ) {
        this.objectMapper = objectMapper;
        this.idempotencyRecordRepository = idempotencyRecordRepository;
    }

    @Transactional("checkpointTransactionManager")
    public boolean insertIfNotExists(String key, int ttl) {
        if (idempotencyRecordRepository.existsByKeyAndNotExpired(key)) {
            return false;
        }
        IdempotencyRecord idempotencyRecord = IdempotencyRecord.builder()
                .key(key)
                .createdAt(ZonedDateTime.now())
                .expiresAt(ZonedDateTime.now().plusSeconds(ttl))
                .data(buildIdempotencyRecordData())
                .build();
        idempotencyRecordRepository.save(idempotencyRecord);
        return true;
    }

    @Transactional("checkpointTransactionManager")
    public boolean exists(String key) {
        return idempotencyRecordRepository.existsByKeyAndNotExpired(key);
    }

    @Transactional("checkpointTransactionManager")
    public boolean delete(String key) {
        return idempotencyRecordRepository.deleteByKeyAndNotExpired(key) > 0;
    }

    @Scheduled(
            fixedRateString = "${qip.idempotency.expired-records-deletion-rate:300}",
            timeUnit = TimeUnit.SECONDS
    )
    @Transactional("checkpointTransactionManager")
    public void deleteExpired() {
        log.debug("Deleting expired idempotency records.");
        idempotencyRecordRepository.deleteExpired();
    }

    private JsonNode buildIdempotencyRecordData() {
        IdempotencyRecordData data = IdempotencyRecordData.builder()
                .status(IdempotencyRecordStatus.RECEIVED)
                .build();
        return objectMapper.valueToTree(data);
    }
}

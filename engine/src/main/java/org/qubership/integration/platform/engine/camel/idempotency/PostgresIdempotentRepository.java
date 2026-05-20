package org.qubership.integration.platform.engine.camel.idempotency;

import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.apache.camel.api.management.ManagedOperation;
import org.apache.camel.spi.IdempotentRepository;
import org.apache.camel.support.service.ServiceSupport;
import org.qubership.integration.platform.engine.service.IdempotencyRecordService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import static org.qubership.integration.platform.engine.model.constants.CamelConstants.SYSTEM_PROPERTY_PREFIX;

@Slf4j
@Component("idempotentRepository")
public class PostgresIdempotentRepository extends ServiceSupport implements IdempotentRepository {
    private static final Integer DEFAULT_KEY_EXPIRY = 600;
    private static final String EXPIRY_PROPERTY = SYSTEM_PROPERTY_PREFIX + "keyExpiry";

    private final IdempotencyRecordService idempotencyRecordService;

    @Autowired
    public PostgresIdempotentRepository(IdempotencyRecordService idempotencyRecordService) {
        this.idempotencyRecordService = idempotencyRecordService;
    }

    @Override
    @ManagedOperation(description = "Adds the key to the store")
    public boolean add(String key) {
        return addKeyToStore(key, DEFAULT_KEY_EXPIRY);
    }

    @Override
    @ManagedOperation(description = "Adds the key to the store")
    public boolean add(Exchange exchange, String key) {
        int ttl = exchange.getProperty(EXPIRY_PROPERTY, DEFAULT_KEY_EXPIRY, Integer.class);
        if (ttl <= 0) {
            throw new IllegalArgumentException("TTL must be greater than 0");
        }
        return addKeyToStore(key, ttl);
    }

    private boolean addKeyToStore(String key, int ttl) {
        return idempotencyRecordService.insertIfNotExists(key, ttl);
    }

    @Override
    @ManagedOperation(description = "Does the store contain the given key")
    public boolean contains(String key) {
        return idempotencyRecordService.exists(key);
    }

    @Override
    @ManagedOperation(description = "Remove the key from the store")
    public boolean remove(String key) {
        return idempotencyRecordService.delete(key);
    }

    @Override
    @ManagedOperation(description = "Clear the store")
    public void clear() {
        // We are not deleting keys on stop.
    }

    @Override
    public boolean confirm(String key) {
        return true;
    }
}

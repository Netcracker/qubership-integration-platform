package org.qubership.integration.platform.engine.camel.idempotency;

import org.apache.camel.api.management.ManagedOperation;
import org.apache.camel.spi.IdempotentRepository;
import org.apache.camel.support.service.ServiceSupport;
import org.qubership.integration.platform.engine.service.IdempotencyRecordService;

public class PostgresIdempotentRepository extends ServiceSupport implements IdempotentRepository {
    private final IdempotencyRecordService idempotencyRecordService;
    private final IdempotentRepositoryParameters parameters;

    public PostgresIdempotentRepository(
            IdempotencyRecordService idempotencyRecordService,
            IdempotentRepositoryParameters parameters
    ) {
        this.idempotencyRecordService = idempotencyRecordService;
        this.parameters = parameters;
    }

    @Override
    @ManagedOperation(description = "Adds the key to the store")
    public boolean add(String key) {
        try {
            return idempotencyRecordService.insertIfNotExists(key, parameters.getTtl());
        } catch (Exception exception) {
            return false;
        }
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

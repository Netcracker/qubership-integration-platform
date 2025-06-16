package org.qubership.integration.platform.engine.configuration;

import org.apache.camel.spi.IdempotentRepository;
import org.qubership.integration.platform.engine.camel.idempotency.IdempotentRepositoryParameters;
import org.qubership.integration.platform.engine.camel.idempotency.PostgresIdempotentRepository;
import org.qubership.integration.platform.engine.service.IdempotencyRecordService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.function.Function;

@Configuration
public class IdempotentRepositoryConfiguration {
    @Bean
    @ConditionalOnMissingBean(name = "idempotentRepositoryFactory")
    Function<IdempotentRepositoryParameters, IdempotentRepository> idempotentRepository(
            IdempotencyRecordService idempotencyRecordService
    ) {
        return keyParameters -> new PostgresIdempotentRepository(
                idempotencyRecordService,
                keyParameters
        );
    }
}

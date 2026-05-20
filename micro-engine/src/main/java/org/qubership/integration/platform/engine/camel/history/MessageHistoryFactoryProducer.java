package org.qubership.integration.platform.engine.camel.history;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Named;
import org.apache.camel.impl.engine.DefaultMessageHistoryFactory;
import org.apache.camel.spi.MessageHistoryFactory;

import java.util.function.Predicate;

@ApplicationScoped
public class MessageHistoryFactoryProducer {
    @Produces
    @Named("messageHistoryFactory")
    public MessageHistoryFactory getMessageHistoryFactory(
            Predicate<FilteringMessageHistoryFactory.FilteringEntity> camelMessageHistoryFilter
    ) {
        return new FilteringMessageHistoryFactory(
                camelMessageHistoryFilter,
                new DefaultMessageHistoryFactory()
        );
    }
}

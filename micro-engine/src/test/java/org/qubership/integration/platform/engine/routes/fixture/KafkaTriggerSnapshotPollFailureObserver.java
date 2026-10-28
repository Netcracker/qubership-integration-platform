package org.qubership.integration.platform.engine.routes.fixture;

import org.apache.camel.Exchange;
import org.apache.camel.spi.CamelEvent;
import org.apache.camel.support.EventNotifierSupport;

import java.util.function.Consumer;
import java.util.function.Supplier;

final class KafkaTriggerSnapshotPollFailureObserver extends EventNotifierSupport {
    private final Supplier<String> routeId;
    private final Consumer<Throwable> failures;

    KafkaTriggerSnapshotPollFailureObserver(Supplier<String> routeId, Consumer<Throwable> failures) {
        this.routeId = routeId;
        this.failures = failures;
    }

    @Override
    public boolean isEnabled(CamelEvent event) {
        return event instanceof CamelEvent.ExchangeCreatedEvent;
    }

    @Override
    public void notify(CamelEvent event) {
        if (event instanceof CamelEvent.ExchangeCreatedEvent created) {
            Exchange exchange = created.getExchange();
            if (routeId.get().equals(exchange.getFromRouteId())
                    && Boolean.TRUE.equals(exchange.getProperty(Exchange.ERRORHANDLER_BRIDGE))) {
                failures.accept(exchange.getException());
            }
        }
    }
}

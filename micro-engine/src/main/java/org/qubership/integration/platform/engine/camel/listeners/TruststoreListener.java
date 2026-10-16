package org.qubership.integration.platform.engine.camel.listeners;

import jakarta.enterprise.inject.spi.CDI;
import org.apache.camel.k.Runtime;
import org.apache.camel.k.listener.AbstractPhaseListener;
import org.qubership.integration.platform.engine.configuration.TruststoreConfiguration;

// Writes the truststore before the predeploy check and the Kafka clients of the routes read it.
public class TruststoreListener extends AbstractPhaseListener {
    public TruststoreListener() {
        super(Runtime.Phase.ConfigureContext);
    }

    @Override
    protected void accept(Runtime runtime) {
        CDI.current().select(TruststoreConfiguration.class).get().buildTruststore();
    }
}

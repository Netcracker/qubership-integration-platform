package org.qubership.integration.platform.engine.routes.fixture;

import jakarta.jms.ConnectionFactory;

import java.util.Hashtable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.Name;
import javax.naming.NameNotFoundException;
import javax.naming.NamingException;
import javax.naming.NoInitialContextException;
import javax.naming.spi.InitialContextFactory;

public final class SnapshotJmsInitialContextFactory implements InitialContextFactory {
    static final String PROVIDER_URL = "snapshot:jms";
    static final String CONNECTION_FACTORY_NAME = "jms/BulkQuotationRequest/connectionfactory";

    private static final Map<String, ConnectionFactory> FACTORIES_BY_PROVIDER_URL = new ConcurrentHashMap<>();

    static void activate(String providerUrl, ConnectionFactory connectionFactory) {
        FACTORIES_BY_PROVIDER_URL.put(providerUrl, connectionFactory);
    }

    static void deactivate(String providerUrl, ConnectionFactory connectionFactory) {
        FACTORIES_BY_PROVIDER_URL.remove(providerUrl, connectionFactory);
    }

    @Override
    public Context getInitialContext(Hashtable<?, ?> environment) throws NamingException {
        String providerUrl = String.valueOf(environment.get(Context.PROVIDER_URL));
        ConnectionFactory connectionFactory = FACTORIES_BY_PROVIDER_URL.get(providerUrl);
        if (connectionFactory == null) {
            throw new NoInitialContextException("No snapshot JMS fixture is registered for '" + providerUrl + "'.");
        }
        return new SnapshotJmsContext(connectionFactory, environment);
    }

    private static final class SnapshotJmsContext extends InitialContext {
        private final ConnectionFactory connectionFactory;
        private final Hashtable<Object, Object> properties = new Hashtable<>();

        private SnapshotJmsContext(ConnectionFactory connectionFactory, Hashtable<?, ?> environment) throws NamingException {
            super(true);
            this.connectionFactory = connectionFactory;
            environment.forEach(properties::put);
        }

        @Override
        public Object lookup(String name) throws NamingException {
            if (CONNECTION_FACTORY_NAME.equals(name)) {
                return connectionFactory;
            }
            // Destinations use the production resolver's fallback through the component's own JMS session.
            throw new NameNotFoundException("Snapshot JMS name '" + name + "' is not bound.");
        }

        @Override
        public Object lookup(Name name) throws NamingException {
            return lookup(name.toString());
        }

        @Override
        public Hashtable<?, ?> getEnvironment() {
            return new Hashtable<>(properties);
        }

        @Override
        public void close() {
        }
    }
}

package org.qubership.integration.platform.engine.routes.fixture;

import jakarta.jms.ConnectionFactory;
import jakarta.jms.Destination;
import org.apache.qpid.jms.JmsQueue;
import org.apache.qpid.jms.JmsTopic;

import java.util.Hashtable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import javax.naming.AuthenticationException;
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

    private static final Map<String, Registration> REGISTRATIONS = new ConcurrentHashMap<>();

    static void activate(String providerUrl, ConnectionFactory connectionFactory) {
        REGISTRATIONS.put(providerUrl, new Registration(connectionFactory));
    }

    static void requireCredentials(String providerUrl, String username, String password) {
        Registration registration = registration(providerUrl);
        registration.username = username;
        registration.password = password;
    }

    static void requireAnonymous(String providerUrl) {
        registration(providerUrl).anonymous = true;
    }

    static int anonymousContexts(String providerUrl) {
        return registration(providerUrl).anonymousContexts.get();
    }

    static void deactivate(String providerUrl, ConnectionFactory connectionFactory) {
        REGISTRATIONS.computeIfPresent(providerUrl, (ignored, registration) ->
                registration.connectionFactory == connectionFactory ? null : registration);
    }

    static void applyBinding(String providerUrl, String name, Map<String, Object> properties) {
        if (!properties.containsKey("jndiBinding")) {
            return;
        }
        Registration registration = registration(providerUrl);
        Object value = properties.get("jndiBinding");
        if (value == null) {
            registration.destinations.remove(name);
            return;
        }
        if (!(value instanceof Map<?, ?> binding)
                || !(binding.get("destination") instanceof String destination) || destination.isBlank()) {
            throw new IllegalArgumentException("JMS jndiBinding requires a destination name.");
        }
        Destination resolved = switch (String.valueOf(binding.get("type"))) {
            case "Queue" -> new JmsQueue(destination);
            case "Topic" -> new JmsTopic(destination);
            default -> throw new IllegalArgumentException("JMS jndiBinding type must be Queue or Topic.");
        };
        registration.destinations.put(name, resolved);
    }

    static int lookupCount(String providerUrl, String name) {
        return registration(providerUrl).lookupCounts.getOrDefault(name, 0);
    }

    static int authenticationFailures(String providerUrl) {
        return registration(providerUrl).authenticationFailures.get();
    }

    private static Registration registration(String providerUrl) {
        Registration registration = REGISTRATIONS.get(providerUrl);
        if (registration == null) {
            throw new IllegalStateException("No snapshot JMS fixture is registered for '" + providerUrl + "'.");
        }
        return registration;
    }

    @Override
    public Context getInitialContext(Hashtable<?, ?> environment) throws NamingException {
        String providerUrl = String.valueOf(environment.get(Context.PROVIDER_URL));
        Registration registration = REGISTRATIONS.get(providerUrl);
        if (registration == null) {
            throw new NoInitialContextException("No snapshot JMS fixture is registered for '" + providerUrl + "'.");
        }
        if (registration.username != null && (!registration.username.equals(environment.get(Context.SECURITY_PRINCIPAL))
                || !registration.password.equals(environment.get(Context.SECURITY_CREDENTIALS)))) {
            registration.authenticationFailures.incrementAndGet();
            throw new AuthenticationException("Snapshot JMS naming credentials were rejected.");
        }
        if (registration.anonymous) {
            if (environment.containsKey(Context.SECURITY_PRINCIPAL) || environment.containsKey(Context.SECURITY_CREDENTIALS)) {
                registration.authenticationFailures.incrementAndGet();
                throw new AuthenticationException("Anonymous snapshot JMS naming must not receive credentials.");
            }
            registration.anonymousContexts.incrementAndGet();
        }
        return new SnapshotJmsContext(registration, environment);
    }

    private static final class Registration {
        private final ConnectionFactory connectionFactory;
        private final Map<String, Destination> destinations = new ConcurrentHashMap<>();
        private final Map<String, Integer> lookupCounts = new ConcurrentHashMap<>();
        private final AtomicInteger authenticationFailures = new AtomicInteger();
        private final AtomicInteger anonymousContexts = new AtomicInteger();
        private volatile boolean anonymous;
        private volatile String username;
        private volatile String password;

        private Registration(ConnectionFactory connectionFactory) {
            this.connectionFactory = connectionFactory;
        }
    }

    private static final class SnapshotJmsContext extends InitialContext {
        private final Registration registration;
        private final Hashtable<Object, Object> properties = new Hashtable<>();

        private SnapshotJmsContext(Registration registration, Hashtable<?, ?> environment) throws NamingException {
            super(true);
            this.registration = registration;
            environment.forEach(properties::put);
        }

        @Override
        public Object lookup(String name) throws NamingException {
            registration.lookupCounts.merge(name, 1, Integer::sum);
            if (CONNECTION_FACTORY_NAME.equals(name)) {
                return registration.connectionFactory;
            }
            Destination destination = registration.destinations.get(name);
            if (destination != null) {
                return destination;
            }
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

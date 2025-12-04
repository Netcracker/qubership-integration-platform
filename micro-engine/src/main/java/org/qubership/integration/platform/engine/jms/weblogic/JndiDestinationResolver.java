package org.qubership.integration.platform.engine.jms.weblogic;

import jakarta.jms.Destination;
import jakarta.jms.JMSException;
import jakarta.jms.Queue;
import jakarta.jms.Session;
import jakarta.jms.Topic;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jms.support.destination.CachingDestinationResolver;
import org.springframework.jms.support.destination.DestinationResolver;
import org.springframework.jms.support.destination.DynamicDestinationResolver;
import org.springframework.jndi.JndiTemplate;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.naming.NamingException;

@Slf4j
public class JndiDestinationResolver implements CachingDestinationResolver {
    @Getter
    @Setter
    private JndiTemplate jndiTemplate = new JndiTemplate();

    private boolean cache = true;

    @Getter
    @Setter
    private boolean fallbackToDynamicDestination = false;

    private DestinationResolver dynamicDestinationResolver = new DynamicDestinationResolver();

    private final Map<String, Destination> destinationCache = new ConcurrentHashMap<>(16);

    public void setCache(boolean cache) {
        this.cache = cache;
    }

    @Override
    public Destination resolveDestinationName(Session session, String destinationName, boolean pubSubDomain)
            throws JMSException {
        Destination dest = this.destinationCache.get(destinationName);
        if (dest != null) {
            validateDestination(dest, destinationName, pubSubDomain);
        } else {
            try {
                dest = lookup(destinationName, Destination.class);
                validateDestination(dest, destinationName, pubSubDomain);
            } catch (NamingException ex) {
                if (log.isDebugEnabled()) {
                    log.debug("Destination [" + destinationName + "] not found in JNDI", ex);
                }
                if (this.fallbackToDynamicDestination) {
                    dest = this.dynamicDestinationResolver.resolveDestinationName(session, destinationName,
                            pubSubDomain);
                } else {
                    throw new RuntimeException(
                            "Destination [" + destinationName + "] not found in JNDI", ex);
                }
            }
            if (this.cache) {
                this.destinationCache.put(destinationName, dest);
            }
        }
        return dest;
    }

    protected void validateDestination(Destination destination, String destinationName, boolean pubSubDomain) {
        Class<?> targetClass = Queue.class;
        if (pubSubDomain) {
            targetClass = Topic.class;
        }
        if (!targetClass.isInstance(destination)) {
            throw new RuntimeException(
                    "Destination [" + destinationName + "] is not of expected type [" + targetClass.getName() + "]");
        }
    }

    @Override
    public void removeFromCache(String destinationName) {
        this.destinationCache.remove(destinationName);
    }

    @Override
    public void clearCache() {
        this.destinationCache.clear();
    }

    protected <T> T lookup(String jndiName, Class<T> requiredType) throws NamingException {
        T jndiObject = getJndiTemplate().lookup(jndiName, requiredType);

        if (log.isDebugEnabled()) {
            log.debug("Located object with JNDI name [" + jndiName + "]");
        }
        return jndiObject;
    }
}

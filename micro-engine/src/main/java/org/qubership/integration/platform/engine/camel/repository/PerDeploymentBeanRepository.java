package org.qubership.integration.platform.engine.camel.repository;

import jakarta.enterprise.context.ApplicationScoped;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.spi.BeanRepository;
import org.apache.camel.spi.Registry;
import org.apache.camel.support.SimpleRegistry;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@ApplicationScoped
@Slf4j
public class PerDeploymentBeanRepository implements BeanRepository {

    private final Map<String, Registry> registries = new ConcurrentHashMap<>();

    @Override
    public Object lookupByName(String name) {
        return registries.values().stream()
                .map(registry -> registry.lookupByName(name))
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    @Override
    public <T> T lookupByNameAndType(String name, Class<T> type) {
        return registries.values().stream()
                .map(registry -> registry.lookupByNameAndType(name, type))
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    @Override
    public <T> Map<String, T> findByTypeWithName(Class<T> type) {
        return registries.values().stream()
                .flatMap(registry -> registry.findByTypeWithName(type).entrySet().stream())
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (a, b) -> {
                            log.warn("Beans with same name and type exist in camel registry");
                            return a;
                        }
                ));
    }

    @Override
    public <T> Set<T> findByType(Class<T> type) {
        return registries.values().stream()
                .flatMap(registry -> registry.findByType(type).stream())
                .collect(Collectors.toSet());
    }

    public Registry getRegistry(String deploymentId) {
        return registries.computeIfAbsent(deploymentId, id -> new SimpleRegistry());
    }

    public void removeRegistry(String deploymentId) {
        registries.remove(deploymentId);
    }
}

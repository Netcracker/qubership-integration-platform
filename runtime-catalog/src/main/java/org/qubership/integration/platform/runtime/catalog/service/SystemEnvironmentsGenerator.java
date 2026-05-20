package org.qubership.integration.platform.runtime.catalog.service;

import org.apache.commons.lang3.StringUtils;
import org.qubership.integration.platform.runtime.catalog.model.system.ServiceEnvironment;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.Environment;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.IntegrationSystem;
import org.qubership.integration.platform.runtime.catalog.service.mapping.ServiceEnvironmentMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import static org.qubership.integration.platform.runtime.catalog.model.system.IntegrationSystemType.*;

@Service
public class SystemEnvironmentsGenerator {

    private final SystemService systemService;
    private final ServiceEnvironmentMapper serviceEnvironmentMapper;

    @Autowired
    public SystemEnvironmentsGenerator(SystemService systemService, ServiceEnvironmentMapper serviceEnvironmentMapper) {
        this.systemService = systemService;
        this.serviceEnvironmentMapper = serviceEnvironmentMapper;
    }

    public List<ServiceEnvironment> generateSystemEnvironments(Collection<String> ids) {
        List<IntegrationSystem> systems = ids.stream()
                .map(systemService::getByIdOrNull).filter(Objects::nonNull)
                .collect(Collectors.toList());
        List<ServiceEnvironment> result = getActiveEnvironmentsBySystems(systems).stream()
                .map(serviceEnvironmentMapper::toServiceEnvironment).collect(Collectors.toList());

        for (IntegrationSystem system : systems) {
            if (result.stream().anyMatch(environment -> environment.getSystemId().equals(system.getId()))) {
                continue;
            }
            ServiceEnvironment serviceEnvironment = new ServiceEnvironment();
            serviceEnvironment.setSystemId(system.getId());
            serviceEnvironment.setNotActivated(true);
            result.add(serviceEnvironment);
        }
        return result;
    }

    public List<Environment> getActiveEnvironmentsBySystems(List<IntegrationSystem> systems) {
        return systems.stream().map(system -> {
            List<Environment> systemEnvironments = system.getEnvironments();
            if (systemEnvironments == null || systemEnvironments.isEmpty()) {
                return null;
            }

            return switch (system.getIntegrationSystemType()) {
                case INTERNAL -> systemEnvironments.get(0);
                case IMPLEMENTED -> {
                    String activeId = system.getActiveEnvironmentId();
                    yield StringUtils.isBlank(activeId)
                            ? systemEnvironments.get(0)
                            : systemEnvironments.stream().filter(environment -> activeId.equals(environment.getId()))
                            .findAny().orElse(null);
                }
                case EXTERNAL -> {
                    String activeId = system.getActiveEnvironmentId();
                    yield StringUtils.isBlank(activeId)
                            ? null
                            : systemEnvironments.stream().filter(environment -> activeId.equals(environment.getId()))
                            .findAny().orElse(null);
                }
                default -> null;
            };
        }).filter(Objects::nonNull).collect(Collectors.toList());
    }
}

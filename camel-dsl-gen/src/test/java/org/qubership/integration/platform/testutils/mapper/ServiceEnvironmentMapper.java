package org.qubership.integration.platform.testutils.mapper;

import org.qubership.integration.platform.chain.impl.ServiceEnvironmentImpl;
import org.qubership.integration.platform.testutils.dto.ServiceEnvironmentDTO;
import org.springframework.boot.test.context.TestComponent;

import static java.util.Objects.isNull;

@TestComponent
public class ServiceEnvironmentMapper {
    public ServiceEnvironmentImpl toEntity(ServiceEnvironmentDTO serviceEnvironmentDTO) {
        if (isNull(serviceEnvironmentDTO)) {
            return null;
        }
        ServiceEnvironmentImpl serviceEnvironment = new ServiceEnvironmentImpl();
        serviceEnvironment.setId(serviceEnvironmentDTO.getId());
        serviceEnvironment.setName(serviceEnvironmentDTO.getName());
        serviceEnvironment.setDescription(serviceEnvironmentDTO.getDescription());
        serviceEnvironment.setAddress(serviceEnvironmentDTO.getAddress());
        serviceEnvironment.setSourceType(serviceEnvironmentDTO.getSourceType());
        serviceEnvironment.setProperties(serviceEnvironmentDTO.getProperties());
        serviceEnvironment.setActivated(!serviceEnvironmentDTO.isNotActivated());
        return serviceEnvironment;
    }
}

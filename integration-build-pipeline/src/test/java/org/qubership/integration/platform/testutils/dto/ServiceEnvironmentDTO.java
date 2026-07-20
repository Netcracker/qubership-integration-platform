package org.qubership.integration.platform.testutils.dto;

import lombok.Data;
import org.qubership.integration.platform.chain.model.EnvironmentSourceType;

import java.sql.Timestamp;
import java.util.Map;

@Data
public class ServiceEnvironmentDTO {
    private String id;
    private String name;
    private String description;
    private String systemId;
    private String address;
    private EnvironmentSourceType sourceType;
    private Map<String, Object> properties;
    private boolean notActivated;
    private Timestamp createdWhen;
    private Timestamp modifiedWhen;
}

package org.qubership.integration.platform.runtime.catalog.model.system.asyncapi.v3;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.Map;

@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class V3Channel {
    private String address;
    private String description;
    private Map<String, Object> messages;
    private Map<String, Object> parameters;
    private Map<String, Object> bindings;
    private List<Object> servers;
}

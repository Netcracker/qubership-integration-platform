package org.qubership.integration.platform.runtime.catalog.model.system.asyncapi.v3;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.qubership.integration.platform.runtime.catalog.model.system.asyncapi.Info;

import java.util.Map;

@Getter
@Setter
@ToString
@JsonIgnoreProperties(ignoreUnknown = true)
public class AsyncapiV3Specification {
    private String asyncapi;
    private Info info;
    private Map<String, V3Server> servers;
    private Map<String, V3Channel> channels;
    private Map<String, V3Operation> operations;
    private Map<String, Object> components;
}

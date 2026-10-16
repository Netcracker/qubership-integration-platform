package org.qubership.integration.platform.parsers.model.asyncapi.v3;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
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

    @JsonProperty("x-maas-classifier-name")
    private String maasClassifierName;
}

package org.qubership.integration.platform.runtime.catalog.model.system.asyncapi.v3;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.Map;

@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class V3Operation {
    private String action;
    private V3ChannelRef channel;
    private String summary;
    private String operationId;
    private List<Map<String, Object>> messages;
    private V3Reply reply;

    @JsonProperty("x-maas-classifier-name")
    private String maasClassifierName;
}

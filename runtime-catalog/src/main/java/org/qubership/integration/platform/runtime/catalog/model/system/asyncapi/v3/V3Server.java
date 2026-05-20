package org.qubership.integration.platform.runtime.catalog.model.system.asyncapi.v3;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class V3Server {
    private String host;
    private String pathname;
    private String protocol;

    @JsonProperty("x-maas-instance")
    private String maasInstanceId;

    public String toUrl() {
        String result = host != null ? host : "";
        if (pathname != null) {
            result += pathname;
        }
        return result;
    }
}

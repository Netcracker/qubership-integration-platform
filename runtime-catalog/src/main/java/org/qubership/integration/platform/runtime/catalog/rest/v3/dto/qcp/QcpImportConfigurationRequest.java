package org.qubership.integration.platform.runtime.catalog.rest.v3.dto.qcp;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class QcpImportConfigurationRequest {

    private String id;
    private QcpPackageContent packageContent;
}

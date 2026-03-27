package org.qubership.integration.platform.runtime.catalog.model.dto.diagnostic;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
public class DuplicateAsyncTriggersDTO {
    private String chainId;
    private String id;
    private String groupId;
    private String topic;
}

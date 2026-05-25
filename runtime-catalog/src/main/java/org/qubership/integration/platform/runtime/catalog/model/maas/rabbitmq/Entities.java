package org.qubership.integration.platform.runtime.catalog.model.maas.rabbitmq;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import lombok.*;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(Include.NON_EMPTY)
public class Entities {
    @Builder.Default
    private List<Map<String, Object>> exchanges = Collections.emptyList();
    @Builder.Default
    private List<Map<String, Object>> queues = Collections.emptyList();
    @Builder.Default
    private List<Map<String, Object>> bindings = Collections.emptyList();
    @Builder.Default
    private List<Map<String, Object>> policies = Collections.emptyList();
}

package org.qubership.integration.platform.engine.metadata;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class McpTriggerInfo {
     private String name;
     private String title;
     private String description;
     private String inputSchema;
     private String outputSchema;
     private Boolean readOnly;
     private Boolean destructive;
     private Boolean idempotent;
     private Boolean openWorld;
     private Boolean requiresLocal;
}

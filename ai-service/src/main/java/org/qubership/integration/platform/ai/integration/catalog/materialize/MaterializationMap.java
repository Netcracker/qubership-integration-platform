package org.qubership.integration.platform.ai.integration.catalog.materialize;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;

/** Maps plan graph node ids to catalog element ids after skeleton materialization. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MaterializationMap(String chainId, Map<String, String> nodeIdToElementId) {}

package org.qubership.integration.platform.ai.qipknowledge.artifact;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Structural role obligation for an element skeleton (no behavioral properties). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ElementRole(
    String roleId,
    String elementType,
    String parentRoleId,
    int minimumCount,
    Integer maximumCount) {}

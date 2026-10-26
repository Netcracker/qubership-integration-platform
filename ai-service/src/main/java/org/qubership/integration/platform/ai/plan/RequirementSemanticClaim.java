package org.qubership.integration.platform.ai.plan;

import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.ClaimEvidenceInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.Polarity;

/**
 * Source-backed claim. Kind, interaction IDs, mapping, element intent, and exact values stay on
 * the requirement projection and may change during repair.
 */
public record RequirementSemanticClaim(
    String sourceFactId,
    String text,
    Polarity polarity,
    ClaimEvidenceInput evidence) {}

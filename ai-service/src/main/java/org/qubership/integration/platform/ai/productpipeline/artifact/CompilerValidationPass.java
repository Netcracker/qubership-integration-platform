package org.qubership.integration.platform.ai.productpipeline.artifact;

import org.qubership.integration.platform.ai.qipknowledge.validation.ValidationResult;

/** One deterministic compiler validation pass result keyed by validator skill id. */
public record CompilerValidationPass(String validatorSkillId, ValidationResult result) {}

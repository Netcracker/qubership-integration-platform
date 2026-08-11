package org.qubership.integration.platform.ai.qipknowledge.pack;

/** Classification of files discovered during QIP knowledge pack scanning. */
public enum QipKnowledgePackFileKind {
  SKILL,
  SKILL_CATALOG,
  RUNTIME_SKILL_INDEX,
  SKILL_PRIVATE_MARKER,
  SKILL_SOURCE_SPECIFICATION,
  SKILL_SPECIFICATION,
  RUNTIME_PACKAGE_ARTIFACT,
  KNOWLEDGE_AI,
  KNOWLEDGE_GRAMMAR,
  KNOWLEDGE_CORPORATE,
  KNOWLEDGE_GENERIC,
  EXAMPLE,
  GENERATED_EXAMPLE,
  OTHER
}

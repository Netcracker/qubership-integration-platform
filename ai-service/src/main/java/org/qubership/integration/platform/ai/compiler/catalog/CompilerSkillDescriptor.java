package org.qubership.integration.platform.ai.compiler.catalog;

import java.util.List;

/** One compiler skill entry after merging runtime index, catalog, source metadata, and markers. */
public record CompilerSkillDescriptor(
    String name,
    String category,
    String path,
    boolean runtimeSkill,
    boolean publicApi,
    boolean privateMarker,
    CompilerSkillDisposition disposition,
    List<String> sourcePaths,
    String substrate,
    List<String> consumes,
    List<String> produces,
    List<String> dependsOn) {

  public CompilerSkillDescriptor {
    sourcePaths = sourcePaths == null ? List.of() : List.copyOf(sourcePaths);
    consumes = consumes == null ? List.of() : List.copyOf(consumes);
    produces = produces == null ? List.of() : List.copyOf(produces);
    dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
  }

  public boolean runnable() {
    return disposition == CompilerSkillDisposition.PUBLIC_RUNTIME
        || disposition == CompilerSkillDisposition.VALIDATOR;
  }

  public CompilerSkillVisibility visibility() {
    if (privateMarker && !publicApi) {
      return CompilerSkillVisibility.PRIVATE;
    }
    return publicApi ? CompilerSkillVisibility.PUBLIC : CompilerSkillVisibility.UNKNOWN;
  }
}

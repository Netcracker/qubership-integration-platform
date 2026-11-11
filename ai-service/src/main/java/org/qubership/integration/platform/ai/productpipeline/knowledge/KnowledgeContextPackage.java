package org.qubership.integration.platform.ai.productpipeline.knowledge;

import java.util.List;
import java.util.stream.Collectors;

/** One compiled runtime context package returned by the knowledge sidecar. */
public record KnowledgeContextPackage(
    KnowledgeResponseIdentity identity,
    List<String> capabilities,
    List<CanonicalKnowledgeObject> objects,
    int contentChars) {
  public KnowledgeContextPackage {
    capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
    objects = objects == null ? List.of() : List.copyOf(objects);
  }

  public String renderMarkdown() {
    KnowledgePackageRef ref = identity.packageRef();
    StringBuilder body = new StringBuilder("Runtime Context Package\n");
    body.append("- package: ").append(ref.packageKey()).append('\n');
    body.append("- checksum: ").append(ref.packageChecksum()).append('\n');
    body.append("- capabilities: ")
        .append(capabilities.stream().sorted().collect(Collectors.joining(", ")))
        .append('\n');
    for (CanonicalKnowledgeObject object : objects) {
      body.append("\n## ")
          .append(object.id())
          .append(" - ")
          .append(object.title())
          .append('\n')
          .append(object.content().body() == null ? "" : object.content().body())
          .append('\n');
    }
    return body.toString();
  }
}

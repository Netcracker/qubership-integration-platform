package org.qubership.integration.platform.ai.qipknowledge.pack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** Creates synthetic compiler inputs exclusively under Maven's test output. */
public final class PublicQipKnowledgePackFixture {

  private static final List<String> GENERATORS = List.of(
      "trigger", "error-handling", "auth", "service-call", "routing", "timeout", "retry",
      "script", "composition", "loop", "parallel", "security", "monitoring", "naming",
      "mcp-service", "mcp-trigger", "chain-failure-handler", "file-operations", "sftp-trigger",
      "sds-trigger", "messaging", "context-storage", "xslt", "abac", "transformation");
  private static final List<Integer> IDS = List.of(
      2, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 27, 28);

  private PublicQipKnowledgePackFixture() {}

  public static void main(String[] args) throws Exception {
    Path root = Path.of(args[0]).resolve("integration-platform-skills");
    create(root);
    QipKnowledgePackBuildGenerator.generate(root, Path.of(args[1]), root.resolve("addons"));
  }

  static void create(Path root) throws Exception {
    StringBuilder manifest = new StringBuilder("version: 2\nskills:\n");
    StringBuilder contracts = new StringBuilder("# Synthetic generator contracts\n\n");
    StringBuilder order = new StringBuilder("## Generator Execution Order\n\n");
    StringBuilder mapping = new StringBuilder("# Synthetic rule mapping\n\n## Generator Summary\n\n| Generator | Count | Rules |\n| --- | --- | --- |\n");
    for (int i = 0; i < GENERATORS.size(); i++) {
      String skill = "cip-" + GENERATORS.get(i) + "-generator";
      String id = "GEN-%02d".formatted(IDS.get(i));
      skill(root, skill, id, true);
      manifest.append("  ").append(skill).append(":\n    generatorId: ").append(id).append("\n    wired: true\n");
      contracts.append("## ").append(id).append(": ").append(GENERATORS.get(i)).append(" Generator\n\nSynthetic test contract.\n\n");
      order.append(i + 1).append(". ").append(id).append(" synthetic generator\n");
      mapping.append("| ").append(id).append(" | 1 | R-").append(100 + i).append(" |\n");
      addon(root, skill, skill.equals("cip-script-generator") ? "repairScriptBodies" : "captureGraphPatch", signals(skill));
    }
    for (String name : List.of("chain", "structure")) {
      String skill = "cip-" + name + "-generator";
      String id = "GEN-" + (name.equals("chain") ? "29" : "03");
      skill(root, skill, id, true);
      manifest.append("  ").append(skill).append(":\n    generatorId: ").append(id).append("\n    wired: true\n");
      addon(root, skill, name.equals("structure") ? "captureChainStructure" : "captureChainPlan", List.of("structure_missing"));
    }
    for (String skill : List.of("cip-requirement-analyzer", "cip-pattern-selector", "cip-design-parser",
        "cip-folder-organizer", "cip-deployment-packager", "cip-chain-assembler", "cip-design-planner", "cip-design-executor", "cip-chain-edit-planner")) {
      skill(root, skill, null, false);
    }
    addon(root, "cip-requirement-analyzer", "captureRequirementBrief", List.of("requirements_missing"));
    addon(root, "cip-pattern-selector", "captureSelectedPattern", List.of("pattern_missing"));
    for (String id : List.of("cip-design-planner", "cip-design-executor", "cip-chain-edit-planner")) {
      put(root, "addons/skills/" + id + ".addon.md",
          "# Synthetic process addon\n\n## Runtime metadata\n\n```yaml\nruntime:\n  promoted: true\n  category: runtime\n  runtime-skill: true\n```\n");
    }
    Path discoveryAddon = root.resolve("addons/skills/cip-requirement-analyzer.addon.md");
    Files.writeString(discoveryAddon, Files.readString(discoveryAddon).replace("category: Generator", "category: runtime"));
    put(root, "addons/global/materialization-requirements.yaml",
        "version: 1\nelementRequirements:\n  script:\n    ownerGenerator: cip-script-generator\n    requiredProperties: []\n    examples: {}\n");
    put(root, ".apm/skills/brainstorming/SKILL.md", "# Brainstorming Ideas Into Designs\n\nSynthetic process skill.\n");
    put(root, "addons/manifest.yaml", manifest.toString());
    put(root, "knowledge/ai/GENERATOR_CONTRACTS.md", contracts.append(order).toString());
    put(root, "knowledge/ai/generator-rule-mapping.md", mapping.toString());
    put(root, "knowledge/ai/validation-rules.yaml", "rules: []\n");
    put(root, "addons/global/graph-patch-contract.md", "# Graph patch fixture\n\nCapture a GraphPatch for the current node IDs.\n");
    put(root, "addons/global/runtime-contract.md", "# Runtime fixture\n\nUse typed artifacts.\n");
    put(root, "product-pipelines/capabilities/planning.yaml", "schemaVersion: 1\ncapabilityId: planning\ncapabilityVersion: '1'\nskills:\n  required: [cip-auth-generator]\naddons:\n  required: [cip-auth-generator]\nrules:\n  required: []\n");
    canonicalSources(root);
    var resolver = new org.qubership.integration.platform.ai.productpipeline.packageindex.ProductPipelineDependencyResolver();
    var pins = resolver.resolveClosure(root.getParent(),
        resolver.loadCapabilities(root.resolve("product-pipelines")),
        GENERATORS.stream().map(name -> "cip-" + name + "-generator").toList());
    StringBuilder baseline = new StringBuilder("schemaVersion: 1\nbaselineId: experimental-migration\nbaselineVersion: '1'\nroot: integration-platform-skills\nreferenceEvidence: {}\nmapping: {}\nartifacts:\n");
    for (var pin : pins) {
      baseline.append("  - dependencyId: ").append(pin.dependencyId())
          .append("\n    kind: ").append(pin.kind())
          .append("\n    targetPath: ").append(pin.path())
          .append("\n    targetSha256: ").append(pin.sha256())
          .append("\n    disposition: TARGET_ONLY\n    adaptationReason: Synthetic public fixture\n");
    }
    put(root, "product-pipelines/reference-baseline-v1.yaml", baseline.toString());
    createExport(root.resolve(QipKnowledgeExportValidator.EXPORT_RELATIVE_PATH).getParent());
  }

  private static void canonicalSources(Path root) throws IOException {
    var yaml = new com.fasterxml.jackson.databind.ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
    List<Map<String, Object>> catalog = new ArrayList<>();
    List<Map<String, Object>> dependencies = new ArrayList<>();
    List<Map<String, Object>> packages = new ArrayList<>();
    List<String> skills = new ArrayList<>(List.of("cip-requirement-analyzer", "cip-pattern-selector", "cip-naming-generator", "cip-trigger-generator", "cip-structure-generator"));
    GENERATORS.stream().map(name -> "cip-" + name + "-generator")
        .filter(id -> !skills.contains(id)).forEach(skills::add);
    skills.addAll(List.of("cip-chain-assembler", "cip-element-validator", "cip-structural-validator", "cip-configuration-validator", "cip-security-validator", "cip-quality-validator"));
    for (String id : skills) {
      String phase = id.equals("cip-requirement-analyzer") || id.equals("cip-pattern-selector") ? "Discovery"
          : id.equals("cip-naming-generator") || id.equals("cip-trigger-generator") || id.equals("cip-structure-generator") ? "Planning"
          : id.equals("cip-chain-assembler") ? "Assembly" : id.endsWith("-validator") ? "Validation" : "Generation";
      List<String> deps = id.endsWith("-validator") ? List.of("cip-chain-assembler") : switch (id) {
        case "cip-requirement-analyzer" -> List.of();
        case "cip-pattern-selector", "cip-naming-generator" -> List.of("cip-requirement-analyzer");
        case "cip-trigger-generator" -> List.of("cip-naming-generator");
        case "cip-structure-generator" -> List.of("cip-trigger-generator");
        case "cip-chain-assembler" -> GENERATORS.stream().map(name -> "cip-" + name + "-generator").toList();
        case "cip-structural-validator" -> List.of("cip-chain-assembler");
        default -> List.of("cip-structure-generator");
      };
      List<String> inputs = id.endsWith("-validator") ? List.of("GRAPH_ASSEMBLY_RESULT") : switch (id) {
        case "cip-requirement-analyzer" -> List.of("RAW_USER_REQUEST");
        case "cip-pattern-selector" -> List.of("RAW_USER_REQUEST", "REQUIREMENT_BRIEF");
        case "cip-chain-assembler" -> List.of("CHAIN_STRUCTURE", "GRAPH_PATCH_ARTIFACT");
        case "cip-structural-validator" -> List.of("GRAPH_ASSEMBLY_RESULT");
        default -> List.of("REQUIREMENT_BRIEF");
      };
      List<String> outputs = id.endsWith("-validator") ? List.of("COMPILER_VALIDATION_BUNDLE") : switch (id) {
        case "cip-requirement-analyzer" -> List.of("REQUIREMENT_BRIEF");
        case "cip-pattern-selector" -> List.of("SELECTED_PATTERN", "ELEMENT_SKELETON");
        case "cip-naming-generator" -> List.of("NAMING_MANIFEST");
        case "cip-trigger-generator" -> List.of("CONFIGURED_TRIGGER_SET");
        case "cip-structure-generator" -> List.of("CHAIN_STRUCTURE");
        case "cip-chain-assembler" -> List.of("GRAPH_ASSEMBLY_RESULT", "CHAIN_PLAN_GRAPH");
        case "cip-structural-validator" -> List.of("COMPILER_VALIDATION_BUNDLE");
        default -> List.of("GRAPH_PATCH_ARTIFACT");
      };
      catalog.add(Map.of("name", id, "category", "Generator", "stage", phase, "dependencies", deps, "inputs", inputs, "generated-artifacts", outputs));
      dependencies.add(Map.of("skill", id, "depends-on", deps));
      int index = GENERATORS.indexOf(id.replaceFirst("^cip-", "").replaceFirst("-generator$", ""));
      if (index >= 0 || id.equals("cip-structure-generator")) {
        packages.add(Map.of("canonical-skill", id, "id", id.equals("cip-structure-generator") ? "GEN-03" : "GEN-%02d".formatted(IDS.get(index))));
      }
      Path addon = root.resolve("addons/skills/" + id + ".addon.md");
      if (Files.isRegularFile(addon)) {
        String content = Files.readString(addon);
        content = content.replace("## Runtime metadata", "- Input artifacts: " + inputs.stream().map(a -> "`" + a + "`").collect(java.util.stream.Collectors.joining(", "))
            + "\n- Output artifacts: " + outputs.stream().map(a -> "`" + a + "`").collect(java.util.stream.Collectors.joining(", ")) + "\n\n## Runtime metadata");
        if (id.equals("cip-trigger-generator")) content = content.replace("captureGraphPatch", "captureConfiguredTriggerSet");
        if (id.equals("cip-naming-generator")) content = content.replace("captureGraphPatch", "captureNamingManifest");
        Files.writeString(addon, content);
      }
    }
    put(root, "skills/skill-catalog.yaml", yaml.writeValueAsString(Map.of("normalized-skills", catalog)));
    put(root, "knowledge/runtime-substrate/runtime-dependency-model.yaml", yaml.writeValueAsString(Map.of("model", Map.of("package", "public-test-fixture", "version", "1.0.0"), "skill-dependencies", dependencies)));
    put(root, "knowledge/runtime-substrate/generator-packages.yaml", yaml.writeValueAsString(Map.of("baseline-generators", packages,
        "ownership-envelopes", List.of("cip-mcp-trigger-generator", "cip-routing-generator").stream().map(id -> Map.of("skill", id, "mayAddNodes", false, "mayAddEdges", false, "nodeTypes", List.of(), "properties", Map.of("mcp-trigger", List.of("name", "description", "inputSchema"), "service-call", List.of("propagateContext")))).toList())));
    put(root, "product-pipelines/artifact-schemas.yaml", "artifacts: []\n");
  }

  private static void skill(Path root, String skill, String id, boolean runtime) throws IOException {
    String content = "---\ncategory: Generator\nruntime-skill: " + runtime + "\n"
        + (id == null ? "" : "generator-id: " + id + "\n")
        + "---\n# Synthetic " + skill + "\n\nTest the public compiler contract.\n"
        + (skill.equals("cip-security-generator") ? "## Supported Access Control Types\n\nUse RBAC in this fixture.\n" : "")
        + (skill.equals("cip-folder-organizer") ? "File transport uses chain.yaml.\n" : "");
    put(root, ".apm/skills/" + skill + "/SKILL.md", content);
    put(root, "skills/" + skill + "/SKILL.md", content);
  }

  private static List<String> signals(String skill) {
    return switch (skill) {
      case "cip-naming-generator" -> List.of("always_ready");
      case "cip-trigger-generator" -> List.of("http_trigger_nodes", "incomplete_http_trigger_endpoint");
      case "cip-error-handling-generator" -> List.of("explicit_error_handling", "incomplete_try_catch_nodes");
      case "cip-auth-generator" -> List.of("credentials", "tls");
      case "cip-service-call-generator" -> List.of("service_call_nodes", "backend_integration_intent", "incomplete_service_call_bindings", "incomplete_service_call_error_throwing");
      case "cip-routing-generator" -> List.of("routing_nodes", "branching_without_routing_nodes", "incomplete_routing_nodes");
      case "cip-script-generator" -> List.of("script_nodes_missing_body");
      case "cip-security-generator" -> List.of("rbac", "external_route", "rbac_roles_missing");
      case "cip-transformation-generator" -> List.of("incomplete_header_modification");
      default -> List.of(skill.replace("cip-", "").replace("-generator", "").replace('-', '_') + "_intent");
    };
  }

  private static void addon(Path root, String skill, String tool, List<String> signals) throws IOException {
    put(root, "addons/skills/" + skill + ".addon.md", """
        # Synthetic runtime addon

        ## Plan property encoding

        Call %s before finishing. Configure accessControlType for RBAC. Preserve the original node IDs.
        For an internal route use externalRoute=false.
        No service calls does not disable an owned native sender: configure it without
        inventing a catalog binding.

        ## Catch scripts vs try-path scripts

        Configure catch scripts independently of the try path.

        ## Applicability in ai-service

        - Configure owned elements when present.

        ## Runtime metadata

        ```yaml
        runtime:
          promoted: true
          category: Generator
          runtime-skill: true
          ownership:
            mayAddNodes: false
            mayAddEdges: false
            nodeTypes: []
            chainFields: []
            properties:
              service-call: [propagateContext]
              mcp-trigger: [name, description, inputSchema]
          capture:
            tool: %s
        ```

        ## Readiness signals

        ```yaml
        readiness:
          mode: any
          signals: %s
        ```
        """.formatted(tool, tool, signals));
  }

  private static void put(Path root, String relative, String content) throws IOException {
    Path file = root.resolve(relative);
    Files.createDirectories(file.getParent());
    Files.writeString(file, content);
  }

  static Path createExport(Path root) throws Exception {
    Path export = root.resolve("knowledge-export");
    Path capabilities = export.resolve("capabilities");
    Files.createDirectories(capabilities);
    write(
        export.resolve("manifest.yaml"),
        """
        manifest_schema: "1.0"
        product: CIP
        knowledge_version: "1.2.3"
        compiler_version: "2.0.0"
        runtime_sdk_version: ">=1.0.0 <2.0"
        schema_version: "1.0.0"
        relation_schema_version: "1.0.0"
        total_objects: 1
        integrity:
          package_checksum: sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
        """);
    write(export.resolve("objects.jsonl"), "{\"id\":\"OBJ-1\"}\n");
    write(capabilities.resolve("capability-catalog.yaml"), "capabilities:\n  - id: auth\n");
    write(
        capabilities.resolve("capability-index.yaml"),
        "capability_index:\n  token_to_capability:\n    auth: auth\n");
    write(
        capabilities.resolve("capability-relations.yaml"),
        "capability_relations:\n  auth:\n    contains:\n      - OBJ-1\n");
    write(
        export.resolve("runtime-certification.yaml"),
        """
        certification:
          status: CERTIFIED
          package:
            key: CIP@1.2.3
            manifest_hash: sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
          total_objects: 1
        """);

    List<String> checkedFiles =
        List.of(
            "manifest.yaml",
            "objects.jsonl",
            "capabilities/capability-catalog.yaml",
            "capabilities/capability-index.yaml",
            "capabilities/capability-relations.yaml");
    StringBuilder checksums = new StringBuilder();
    for (String name : checkedFiles) {
      checksums.append(sha256(export.resolve(name))).append("  ").append(name).append('\n');
    }
    write(export.resolve("CHECKSUMS.sha256"), checksums.toString());
    return export;
  }

  private static void write(Path path, String content) throws IOException {
    Files.writeString(path, content, StandardCharsets.UTF_8);
  }

  private static String sha256(Path path) throws IOException, NoSuchAlgorithmException {
    return HexFormat.of()
        .formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
  }
}

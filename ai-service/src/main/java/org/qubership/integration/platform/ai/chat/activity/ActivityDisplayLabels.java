package org.qubership.integration.platform.ai.chat.activity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Maps technical skill and tool names to English gerunds for chat activity rows. */
public final class ActivityDisplayLabels {

  private static final Pattern HTTP_LABEL = Pattern.compile("^[A-Z]+ /.+$");
  private static final Pattern CAMEL_SPLIT =
      Pattern.compile("(?<=[a-z])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])");

  private static final Map<String, String> VERBS =
      Map.ofEntries(
          Map.entry("capture", "Capturing"),
          Map.entry("search", "Searching"),
          Map.entry("list", "Listing"),
          Map.entry("get", "Getting"),
          Map.entry("describe", "Describing"),
          Map.entry("create", "Creating"),
          Map.entry("update", "Updating"),
          Map.entry("delete", "Deleting"),
          Map.entry("repair", "Repairing"),
          Map.entry("select", "Selecting"),
          Map.entry("resolve", "Resolving"),
          Map.entry("approve", "Approving"),
          Map.entry("import", "Importing"),
          Map.entry("patch", "Patching"));

  private static final Map<String, String> EXACT = exactMap();
  private static final List<HttpTemplate> HTTP = httpTemplates();

  private ActivityDisplayLabels() {}

  public static String of(String kind, String technical) {
    if (technical == null || technical.isBlank()) {
      return technical;
    }
    if (!"skill".equals(kind) && !"tool".equals(kind)) {
      return technical;
    }
    String exact = EXACT.get(technical);
    if (exact != null) {
      return exact;
    }
    if (HTTP_LABEL.matcher(technical).matches()) {
      String matched = matchHttp(technical);
      return matched != null ? matched : "Calling the catalog";
    }
    if ("skill".equals(kind)) {
      return skillSuffixFallback(technical);
    }
    return humanizeTool(technical);
  }

  private static String matchHttp(String label) {
    int space = label.indexOf(' ');
    String method = label.substring(0, space);
    String path = label.substring(space + 1);
    String best = null;
    int bestLen = -1;
    for (HttpTemplate template : HTTP) {
      if (!template.method.equals(method)) {
        continue;
      }
      if (template.matches(path) && template.path.length() > bestLen) {
        best = template.label;
        bestLen = template.path.length();
      }
    }
    return best;
  }

  private static String skillSuffixFallback(String skillId) {
    String id = skillId.startsWith("cip-") ? skillId.substring(4) : skillId;
    if (id.endsWith("-generator")) {
      return "Generating " + id.substring(0, id.length() - "-generator".length()).replace('-', ' ');
    }
    if (id.endsWith("-validator")) {
      return "Validating " + id.substring(0, id.length() - "-validator".length()).replace('-', ' ');
    }
    if (id.endsWith("-analyzer")) {
      return "Analyzing " + id.substring(0, id.length() - "-analyzer".length()).replace('-', ' ');
    }
    return humanizeTool(id);
  }

  private static String humanizeTool(String name) {
    List<String> tokens = splitTokens(name);
    if (tokens.isEmpty()) {
      return name;
    }
    String first = tokens.get(0).toLowerCase(Locale.ROOT);
    String gerund = VERBS.get(first);
    StringBuilder out = new StringBuilder();
    if (gerund != null) {
      out.append(gerund);
    } else {
      out.append("Running ").append(first);
    }
    for (int i = 1; i < tokens.size(); i++) {
      out.append(' ').append(tokens.get(i).toLowerCase(Locale.ROOT));
    }
    return out.toString();
  }

  private static List<String> splitTokens(String name) {
    List<String> tokens = new ArrayList<>();
    for (String hyphen : name.split("-")) {
      if (hyphen.isEmpty()) {
        continue;
      }
      for (String part : CAMEL_SPLIT.split(hyphen)) {
        if (!part.isEmpty()) {
          tokens.add(part);
        }
      }
    }
    return tokens;
  }

  private static Map<String, String> exactMap() {
    return Map.ofEntries(
        Map.entry("brainstorming", "Exploring requirements"),
        Map.entry("cip-requirement-analyzer", "Parsing requirements"),
        Map.entry("cip-design-generator", "Generating the design"),
        Map.entry("chain-semantic-design", "Capturing the chain design"),
        Map.entry("cip-design-planner", "Planning the implementation"),
        Map.entry("cip-design-executor", "Executing the plan"),
        Map.entry("cip-pattern-selector", "Selecting a pattern"),
        Map.entry("cip-naming-generator", "Applying naming conventions"),
        Map.entry("cip-trigger-generator", "Configuring triggers"),
        Map.entry("cip-structure-generator", "Building the chain structure"),
        Map.entry("cip-http-trigger-endpoint-generator", "Updating the HTTP endpoint"),
        Map.entry("cip-service-call-generator", "Configuring service calls"),
        Map.entry("cip-script-generator", "Writing scripts"),
        Map.entry("cip-auth-generator", "Configuring authentication"),
        Map.entry("cip-error-handling-generator", "Configuring error handling"),
        Map.entry("cip-chain-assembler", "Assembling the chain"),
        Map.entry("cip-chain-validator", "Validating the chain"),
        Map.entry("cip-element-validator", "Validating elements"),
        Map.entry("cip-structural-validator", "Validating structure"),
        Map.entry("cip-configuration-validator", "Validating configuration"),
        Map.entry("cip-security-validator", "Validating security"),
        Map.entry("cip-quality-validator", "Checking quality"),
        Map.entry("cip-chain-generator", "Generating the chain"),
        Map.entry("cip-routing-generator", "Adding routing"),
        Map.entry("cip-security-generator", "Configuring security"),
        Map.entry("cip-messaging-generator", "Adding messaging"),
        Map.entry("cip-transformation-generator", "Adding transformations"),
        Map.entry("cip-retry-generator", "Configuring retries"),
        Map.entry("cip-timeout-generator", "Configuring timeouts"),
        Map.entry("cip-loop-generator", "Adding loops"),
        Map.entry("cip-parallel-generator", "Adding parallel steps"),
        Map.entry("cip-variable-generator", "Configuring variables"),
        Map.entry("cip-folder-organizer", "Organizing chain folders"),
        Map.entry("cip-deployment-packager", "Packaging for deployment"),
        Map.entry("cip-runtime-context-loader", "Loading runtime context"),
        Map.entry("cip-sds-trigger-generator", "Configuring SDS triggers"),
        Map.entry("cip-sftp-trigger-generator", "Configuring SFTP triggers"),
        Map.entry("cip-mcp-trigger-generator", "Configuring MCP triggers"),
        Map.entry("cip-mcp-service-generator", "Configuring MCP services"),
        Map.entry("cip-quartz-scheduler-generator", "Configuring the scheduler"),
        Map.entry("cip-cache-generator", "Configuring cache"),
        Map.entry("cip-monitoring-generator", "Configuring monitoring"),
        Map.entry("cip-idempotency-generator", "Configuring idempotency"),
        Map.entry("cip-validation-generator", "Configuring message validation"),
        Map.entry("cip-xslt-generator", "Adding XSLT"),
        Map.entry("cip-file-operations-generator", "Adding file operations"),
        Map.entry("cip-context-storage-generator", "Configuring context storage"),
        Map.entry("cip-composition-generator", "Adding chain composition"),
        Map.entry("cip-abac-generator", "Configuring ABAC"),
        Map.entry("cip-chain-failure-handler-generator", "Configuring chain failure handling"),
        Map.entry("materialization", "Creating the chain"),
        Map.entry("specification-import", "Importing the specification"),
        Map.entry("chain-edit-intent", "Understanding the change"),
        Map.entry("generator-plan-manifest", "Preparing generator plans"),
        Map.entry("searchCatalogSystems", "Searching for a service"),
        Map.entry("describeBoundOperation", "Loading the bound operation"),
        Map.entry("getApiSpecifications", "Loading specifications"),
        Map.entry("listCatalogOperations", "Listing operations"),
        Map.entry("createSystem", "Creating a catalog service"),
        Map.entry("importApiHubSpecToSystem", "Importing a specification"),
        Map.entry("createElement", "Adding a chain element"),
        Map.entry("updateElement", "Updating a chain element"),
        Map.entry("listElements", "Listing chain elements"),
        Map.entry("describeElementPatchSchema", "Loading the element schema"),
        Map.entry("describeElementProperty", "Loading a property schema"),
        Map.entry("searchApiOperations", "Searching API Hub"),
        Map.entry("getApiOperationSpecification", "Loading an API Hub operation"),
        Map.entry("listApiHubPackages", "Listing API Hub packages"),
        Map.entry("getApiHubDocument", "Loading an API Hub document"),
        Map.entry("resolveApiOperation", "Resolving an API operation"),
        Map.entry("selectApiHubCandidate", "Selecting an API Hub candidate"),
        Map.entry("captureRequirementDraft", "Capturing the requirement draft"),
        Map.entry("captureRequirementBrief", "Capturing the requirement brief"),
        Map.entry("captureSelectedPattern", "Capturing the selected pattern"),
        Map.entry("captureChainPlan", "Capturing the chain plan"),
        Map.entry("repairChainPlanPatch", "Repairing the chain plan"),
        Map.entry("captureValidationResult", "Capturing the validation result"),
        Map.entry("captureChainStructure", "Capturing the chain structure"),
        Map.entry("captureChainEditSubgraph", "Capturing the chain edit"),
        Map.entry("captureGraphPatch", "Capturing the graph patch"),
        Map.entry("captureConfiguredTriggerSet", "Capturing configured triggers"),
        Map.entry("captureNamingManifest", "Capturing the naming manifest"),
        Map.entry("approveCandidate", "Approving the candidate"),
        Map.entry("repairScriptBodies", "Editing scripts"));
  }

  private static List<HttpTemplate> httpTemplates() {
    return List.of(
        new HttpTemplate("POST", "/v1/systems/search", "Searching for a service"),
        new HttpTemplate("POST", "/v1/systems", "Creating a catalog service"),
        new HttpTemplate("GET", "/v1/systems/{id}", "Loading a service"),
        new HttpTemplate("GET", "/v1/systems/{id}/environments", "Loading environments"),
        new HttpTemplate("POST", "/v1/systems/{id}/environments", "Creating an environment"),
        new HttpTemplate("GET", "/v1/models", "Loading specifications"),
        new HttpTemplate("GET", "/v1/models/{id}", "Loading a specification"),
        new HttpTemplate("GET", "/v1/operations", "Listing operations"),
        new HttpTemplate("GET", "/v1/operations/{id}", "Loading an operation"),
        new HttpTemplate("POST", "/v1/chains", "Creating the chain"),
        new HttpTemplate("GET", "/v1/chains/{id}", "Loading the chain"),
        new HttpTemplate("DELETE", "/v1/chains/{id}", "Deleting the chain"),
        new HttpTemplate("POST", "/v1/folders/search", "Searching folders"),
        new HttpTemplate("POST", "/v1/catalog/chains/{id}/snapshots", "Creating a snapshot"),
        new HttpTemplate("GET", "/v1/catalog/chains/{id}/snapshots", "Listing snapshots"),
        new HttpTemplate("GET", "/v1/library/{id}", "Loading an element type"),
        new HttpTemplate("POST", "/v1/chains/{id}/elements", "Adding a chain element"),
        new HttpTemplate("GET", "/v1/chains/{id}/elements", "Listing chain elements"),
        new HttpTemplate("GET", "/v1/chains/{id}/elements/{id}", "Loading a chain element"),
        new HttpTemplate("PATCH", "/v1/chains/{id}/elements/{id}", "Updating a chain element"),
        new HttpTemplate("DELETE", "/v1/chains/{id}/elements", "Deleting chain elements"),
        new HttpTemplate("POST", "/v1/chains/{id}/elements/transfer", "Moving chain elements"),
        new HttpTemplate("POST", "/v1/chains/{id}/dependencies", "Adding a connection"),
        new HttpTemplate("GET", "/v1/chains/{id}/dependencies", "Listing connections"),
        new HttpTemplate("DELETE", "/v1/chains/{id}/dependencies", "Deleting connections"),
        new HttpTemplate("POST", "/v1/specificationGroups/import", "Importing a specification"),
        new HttpTemplate("GET", "/v1/import/{id}", "Checking import status"));
  }

  private record HttpTemplate(String method, String path, String label) {
    boolean matches(String actualPath) {
      String[] want = path.split("/", -1);
      String[] got = actualPath.split("/", -1);
      if (want.length != got.length) {
        return false;
      }
      for (int i = 0; i < want.length; i++) {
        if ("{id}".equals(want[i])) {
          if (got[i].isEmpty()) {
            return false;
          }
          continue;
        }
        if (!want[i].equals(got[i])) {
          return false;
        }
      }
      return true;
    }
  }
}

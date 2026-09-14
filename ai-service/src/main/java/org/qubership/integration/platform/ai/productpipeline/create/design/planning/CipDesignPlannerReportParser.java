package org.qubership.integration.platform.ai.productpipeline.create.design.planning;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses the exact Markdown process report from immutable {@code cip-design-planner}. */
public final class CipDesignPlannerReportParser {

  static final String APPROVAL_SENTENCE =
      DesignPlanReportRenderer.APPROVAL_SENTENCE;

  private static final Pattern STEP_LINE = Pattern.compile("^(\\d+)\\.\\s+(.+)$");
  private static final Pattern SKILL_ID = Pattern.compile("\\b(cip-[a-z0-9-]+)\\b");
  private static final Pattern TOOL_OP =
      Pattern.compile(
          "\\b(search_rest_api_operations|get_rest_api_operations_specification|"
              + "get_api_operation_specification|search_api_operations)\\b");
  private static final Pattern API_RELEASE = Pattern.compile("\\bversion\\s+(\\d{4}\\.\\d)\\b");
  private static final Pattern OPERATION_DOTTED =
      Pattern.compile(
          "\\b((?:[A-Z][A-Za-z0-9_-]*\\s+){0,3}[A-Z][A-Za-z0-9_-]*)"
              + "\\.([A-Za-z][A-Za-z0-9_]*)\\b");
  private static final Pattern INTERFACE_NAME =
      Pattern.compile("\\binterface\\s+([A-Za-z0-9][A-Za-z0-9 /_-]{0,40})");
  private static final Pattern MAPPING_INTENT_ID =
      Pattern.compile("mappingIntentId=([A-Za-z0-9_-]+)");
  private static final Pattern SERVICE_CALL_ID =
      Pattern.compile("serviceCallId=([A-Za-z0-9_-]+)");
  private static final Pattern SERVICE_CALL_ROLE =
      Pattern.compile("serviceCallRole=(PRODUCER|REFERENCE)");
  private static final Pattern REGION_ID = Pattern.compile("regionId=([A-Za-z0-9_-]+)");

  public ParsedPlannerReport parse(String markdown) {
    if (markdown == null || markdown.isBlank()) {
      throw new PlannerReportFormatException("planner report is empty");
    }
    String trimmed = markdown.trim();
    if (!trimmed.contains(APPROVAL_SENTENCE)) {
      throw new PlannerReportFormatException(
          "planner report missing approval sentence: " + APPROVAL_SENTENCE);
    }

    List<ParsedPlannerReport.Step> steps = new ArrayList<>();
    String apiRelease = null;
    for (String rawLine : trimmed.split("\\R")) {
      String line = rawLine.trim();
      if (line.isEmpty() || line.equals(APPROVAL_SENTENCE)) {
        continue;
      }
      Matcher stepMatcher = STEP_LINE.matcher(line);
      if (!stepMatcher.matches()) {
        throw new PlannerReportFormatException(
            "planner report contains a non-numbered line: " + line);
      }
      int ordinal = Integer.parseInt(stepMatcher.group(1));
      if (ordinal != steps.size() + 1) {
        throw new PlannerReportFormatException(
            "planner report step ordinal out of order: expected "
                + (steps.size() + 1)
                + " but was "
                + ordinal);
      }
      String reportText = stepMatcher.group(2).trim();
      List<String> skillIds = extractSkillIds(reportText);
      List<String> toolOps = extractToolOps(reportText);
      String serviceCallId = extractToken(SERVICE_CALL_ID, reportText);
      ParsedPlannerReport.ServiceCallRole serviceCallRole = extractServiceCallRole(reportText);
      if (!serviceCallId.isBlank()
          && serviceCallRole == ParsedPlannerReport.ServiceCallRole.NONE) {
        throw new PlannerReportFormatException(
            "planner report step "
                + ordinal
                + " must declare serviceCallRole=PRODUCER or serviceCallRole=REFERENCE");
      }
      if (serviceCallId.isBlank()
          && serviceCallRole != ParsedPlannerReport.ServiceCallRole.NONE) {
        throw new PlannerReportFormatException(
            "planner report step "
                + ordinal
                + " declares a serviceCallRole without serviceCallId=<id>");
      }
      ParsedPlannerReport.OwnerKind ownerKind =
          !toolOps.isEmpty() || reportText.toLowerCase(Locale.ROOT).contains("apihub mcp")
              ? ParsedPlannerReport.OwnerKind.APIHUB_TOOL
              : ParsedPlannerReport.OwnerKind.SKILL;
      if (ownerKind == ParsedPlannerReport.OwnerKind.SKILL && skillIds.isEmpty()) {
        throw new PlannerReportFormatException(
            "planner report step " + ordinal + " is missing an owning skill id");
      }
      if (ownerKind == ParsedPlannerReport.OwnerKind.APIHUB_TOOL && toolOps.isEmpty()) {
        throw new PlannerReportFormatException(
            "planner report step " + ordinal + " is missing an APIHub tool operation");
      }
      if (apiRelease == null) {
        Matcher releaseMatcher = API_RELEASE.matcher(reportText);
        if (releaseMatcher.find()) {
          apiRelease = releaseMatcher.group(1);
        }
      }
      steps.add(
          new ParsedPlannerReport.Step(
              ordinal,
              reportText,
              ownerKind,
              skillIds,
              toolOps,
              List.of(),
              extractOperationQueryHints(reportText),
              extractMappingIntentId(reportText),
              serviceCallId,
              serviceCallRole,
              extractToken(REGION_ID, reportText)));
    }
    if (steps.isEmpty()) {
      throw new PlannerReportFormatException("planner report has no numbered steps");
    }
    return new ParsedPlannerReport(steps, apiRelease);
  }

  private static List<String> extractSkillIds(String reportText) {
    LinkedHashSet<String> ids = new LinkedHashSet<>();
    Matcher matcher = SKILL_ID.matcher(reportText);
    while (matcher.find()) {
      ids.add(matcher.group(1));
    }
    return List.copyOf(ids);
  }

  private static List<String> extractToolOps(String reportText) {
    LinkedHashSet<String> ops = new LinkedHashSet<>();
    Matcher matcher = TOOL_OP.matcher(reportText);
    while (matcher.find()) {
      ops.add(matcher.group(1));
    }
    return List.copyOf(ops);
  }

  private static List<String> extractOperationQueryHints(String reportText) {
    LinkedHashSet<String> hints = new LinkedHashSet<>();
    Matcher dotted = OPERATION_DOTTED.matcher(reportText);
    while (dotted.find()) {
      hints.add(dotted.group(2));
    }
    Matcher interfaceMatcher = INTERFACE_NAME.matcher(reportText);
    while (interfaceMatcher.find()) {
      hints.add(interfaceMatcher.group(1).trim());
    }
    return List.copyOf(hints);
  }

  private static String extractMappingIntentId(String reportText) {
    return extractToken(MAPPING_INTENT_ID, reportText);
  }

  private static String extractToken(Pattern pattern, String reportText) {
    Matcher matcher = pattern.matcher(reportText);
    return matcher.find() ? matcher.group(1) : "";
  }

  private static ParsedPlannerReport.ServiceCallRole extractServiceCallRole(String reportText) {
    String role = extractToken(SERVICE_CALL_ROLE, reportText);
    return role.isBlank()
        ? ParsedPlannerReport.ServiceCallRole.NONE
        : ParsedPlannerReport.ServiceCallRole.valueOf(role);
  }
}

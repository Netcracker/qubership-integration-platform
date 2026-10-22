package org.qubership.integration.platform.ai.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.regex.Pattern;
import org.qubership.integration.platform.ai.plan.RequirementBodyBinding.Authority;
import org.qubership.integration.platform.ai.plan.RequirementBodyBinding.Encoding;
import org.qubership.integration.platform.ai.plan.RequirementBodyBinding.NativeBodyKind;
import org.qubership.integration.platform.ai.plan.mapping.MappingExecutionSite;

/** Recognizes complete source code values; it does not infer behavior or evaluate code. */
public final class RequirementBodySources {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Pattern FENCE = Pattern.compile(
      "(?ms)^[ \\t]*(`{3,}|~{3,})([^\\r\\n]*)\\r?\\n(.*?)^[ \\t]*\\1[ \\t]*(?:\\r?\\n|$)");
  private static final Pattern INLINE = Pattern.compile("`([^`\\r\\n]+)`");
  private static final Pattern JSON_BODY = Pattern.compile("\\\"script\\\"\\s*:\\s*(\\\"(?:\\\\.|[^\\\"\\\\])*\\\")");
  private static final Pattern DECLARATION = Pattern.compile(
      "(?i).*\\b(?:script|code)(?:\\s+body)?\\b[^\\r\\n]*[:=]\\s*$");
  private static final Pattern UNSUPPORTED = Pattern.compile(
      "(?im)^.*(?:\\b(?:exact(?:ly)?|verbatim)\\s+(?:(?:this|the|following)\\s+)*(?:script|code)\\b|\\b(?:script|code)\\s+body\\s*[:=]|^\\s*(?:[\\w-]+\\.)?script\\s*[:=]).*$");

  private RequirementBodySources() {}

  public record Resolution(RequirementBodyBinding binding, String body, String error) {}
  private record Candidate(String turn, int start, int end, Encoding encoding, String body, String owner) {}

  /** Exact metadata additions that can be independently reconstructed from retained author source. */
  public static Set<String> completionPaths(RequirementCaptureInput.DraftInput base,
      RequirementCaptureInput.DraftInput candidate, AuthorSourceEvidence.Packet source) {
    var paths = new LinkedHashSet<String>();
    if (base == null || candidate == null) return paths;
    for (var fact : base.facts()) {
      var resolution = completion(fact, base, source);
      if (resolution == null) continue;
      var matches = candidate.facts().stream().filter(value -> fact.sourceFactId().equals(value.sourceFactId())).toList();
      if (matches.size() != 1) continue;
      var next = matches.getFirst();
      if (next.elementIntent() == null || !Objects.equals(resolution.binding(), next.elementIntent().bodyBinding())
          || !Objects.equals(fact, withoutBinding(next))
          || candidate.facts().stream().map(RequirementCaptureInput.FactInput::elementIntent)
              .filter(Objects::nonNull).filter(value -> fact.elementIntent().elementRef().equals(value.elementRef()))
              .count() != 1
          || candidate.flow().interactions().stream()
              .filter(value -> fact.elementIntent().ownerInteractionId().equals(value.interactionId())).count() != 1) continue;
      paths.add(bindingPath(fact));
    }
    return Set.copyOf(paths);
  }

  static Set<String> missingBindingPaths(RequirementCaptureInput.DraftInput base, AuthorSourceEvidence.Packet source) {
    if (base == null) return Set.of();
    return base.facts().stream().filter(fact -> completion(fact, base, source) != null)
        .map(RequirementBodySources::bindingPath).collect(java.util.stream.Collectors.toUnmodifiableSet());
  }

  /** Removes only independently proved additions for another native correction's exact comparison. */
  static RequirementCaptureInput.DraftInput normalizeCompletedBindings(RequirementCaptureInput.DraftInput candidate,
      Set<String> paths) {
    if (paths.isEmpty()) return candidate;
    return new RequirementCaptureInput.DraftInput(candidate.flow(), candidate.facts().stream()
        .map(fact -> paths.contains(bindingPath(fact)) ? withoutBinding(fact) : fact).toList(),
        candidate.capabilities(), candidate.openQuestions(), candidate.settings());
  }

  private static Resolution completion(RequirementCaptureInput.FactInput fact,
      RequirementCaptureInput.DraftInput base, AuthorSourceEvidence.Packet source) {
    var intent = fact.elementIntent();
    if (source == null || source.generation() == null || source.generation().isBlank() || source.inputs().isEmpty()
        || source.inputs().stream().anyMatch(input -> input.sourceTurnId() == null || input.sourceTurnId().isBlank()
            || input.text() == null)
        || source.inputs().stream().map(AuthorSourceEvidence.Input::sourceTurnId).distinct().count() != source.inputs().size()
        || fact.polarity() != RequirementCaptureInput.Polarity.POSITIVE || intent == null || intent.bodyBinding() != null
        || intent.placement() != RequirementElementIntent.Placement.LOCAL_NODE || !ownsBody(intent, "script")
        || intent.ownerInteractionId() == null || !fact.interactionIds().contains(intent.ownerInteractionId())
        || base.flow().interactions().stream().filter(value -> intent.ownerInteractionId().equals(value.interactionId())).count() != 1
        || base.facts().stream().filter(value -> fact.sourceFactId().equals(value.sourceFactId())).count() != 1) return null;
    var owners = base.facts().stream().map(RequirementCaptureInput.FactInput::elementIntent).filter(Objects::nonNull).toList();
    if (owners.stream().filter(value -> intent.elementRef().equals(value.elementRef())).count() != 1) return null;
    var resolution = resolve(intent, owners, source);
    if (resolution.error() != null || resolution.binding() == null) return null;
    if (resolution.body() != null) {
      var bodies = intent.requestedProperties().stream().filter(value -> ownsBody(intent, value.key())).toList();
      if (bodies.size() != 1 || !Objects.equals(resolution.body(), bodies.getFirst().value())) return null;
    }
    return resolution;
  }

  private static String bindingPath(RequirementCaptureInput.FactInput fact) {
    return "/facts/" + fact.sourceFactId().replace("~", "~0").replace("/", "~1") + "/elementIntent/bodyBinding";
  }

  private static RequirementCaptureInput.FactInput withoutBinding(RequirementCaptureInput.FactInput fact) {
    var intent = fact.elementIntent();
    var copy = new RequirementElementIntent(intent.elementRef(), intent.elementType(), intent.placement(),
        intent.ownerInteractionId(), intent.successorElementRef(), intent.predecessorElementRef(), intent.parentElementRef(),
        intent.requestedName(), intent.requestedProperties(), intent.exactValues(), null);
    return new RequirementCaptureInput.FactInput(fact.sourceFactId(), fact.interactionIds(), fact.kind(), fact.polarity(),
        fact.text(), fact.fieldMapping(), fact.policyConflict(), copy);
  }

  public static boolean ownsBody(RequirementElementIntent intent, String key) {
    return intent != null && MappingExecutionSite.SCRIPT_ELEMENT_TYPE.equals(intent.elementType())
        && MappingExecutionSite.SCRIPT_PROPERTY.equals(key)
        && intent.placement() != RequirementElementIntent.Placement.BEFORE_HOOK
        && intent.placement() != RequirementElementIntent.Placement.AFTER_HOOK;
  }

  /** A missing binding is deliberately not a certificate that a legacy body was generated. */
  public static void requireBinding(RequirementElementIntent intent) {
    var binding = intent.bodyBinding();
    if (binding == null || binding.authority() == null
        || !Objects.equals(intent.elementRef(), binding.elementRef())
        || !MappingExecutionSite.SCRIPT_PROPERTY.equals(binding.propertyKey())
        || binding.bodyKind() != NativeBodyKind.GROOVY_SCRIPT) {
      throw new IllegalArgumentException("Native script body authority is unproven for " + intent.elementRef()
          + ". Reclassify the complete body against retained author source through requirement capture.");
    }
  }

  /** Returns the authority derived from source, independently of the submitted flag or body value. */
  public static Resolution resolve(RequirementElementIntent intent, List<RequirementElementIntent> intents,
      AuthorSourceEvidence.Packet source) {
    List<RequirementElementIntent> owners = intents.stream().filter(value -> ownsBody(value, "script"))
        .collect(java.util.stream.Collectors.toMap(RequirementElementIntent::elementRef,
            value -> value, (first, second) -> first, java.util.LinkedHashMap::new)).values().stream().toList();
    List<Candidate> candidates = new ArrayList<>();
    boolean unproven = false;
    for (var input : source.inputs()) {
      String text = input.text();
      if (text == null) continue;
      List<int[]> covered = new ArrayList<>();
      var fences = FENCE.matcher(text);
      while (fences.find()) {
        String language = fences.group(2).strip();
        if (!language.isEmpty() && !"groovy".equalsIgnoreCase(language)) continue;
        String prefix = prefix(text, fences.start());
        if (language.isEmpty() && !DECLARATION.matcher(prefix).matches()) continue;
        String owner = owner(prefix, owners);
        candidates.add(new Candidate(input.sourceTurnId(), fences.start(3), fences.end(3), Encoding.RAW,
            fences.group(3), owner));
        covered.add(new int[] {fences.start(), fences.end()});
        unproven |= owner == null;
      }
      var inline = INLINE.matcher(text);
      while (inline.find()) {
        if (inside(covered, inline.start())) continue;
        String prefix = prefix(text, inline.start());
        if (!DECLARATION.matcher(prefix).matches()) continue;
        String owner = owner(prefix, owners);
        candidates.add(new Candidate(input.sourceTurnId(), inline.start(1), inline.end(1), Encoding.RAW,
            inline.group(1), owner));
        covered.add(new int[] {inline.start(), inline.end()});
        unproven |= owner == null;
      }
      var json = JSON_BODY.matcher(text);
      List<int[]> objects = objectSpans(text);
      while (json.find()) {
        if (inside(covered, json.start())) continue;
        String owner = null;
        boolean explicitOwner = false;
        for (var object : objects) {
          if (object[0] > json.start() || object[1] < json.end()) continue;
          try {
            var node = JSON.readTree(text.substring(object[0], object[1]));
            String ref = node.path("elementRef").asText(node.path("id").asText(null));
            if (ref != null) {
              explicitOwner = true;
              owner = owners.stream().filter(value -> ref.equals(value.elementRef()))
                  .map(RequirementElementIntent::elementRef).findFirst().orElse(null);
              break;
            }
          } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            // A non-JSON surrounding block does not establish native configuration ownership.
          }
        }
        if (owner == null && !explicitOwner && owners.size() == 1) owner = owners.getFirst().elementRef();
        try {
          candidates.add(new Candidate(input.sourceTurnId(), json.start(1), json.end(1), Encoding.JSON_STRING,
              JSON.readValue(json.group(1), String.class), owner));
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
          unproven = true;
        }
        covered.add(new int[] {json.start(), json.end()});
        unproven |= owner == null;
      }
      var opening = Pattern.compile("(?m)^[ \\t]*(?:`{3,}|~{3,})[ \\t]*groovy\\b", Pattern.CASE_INSENSITIVE).matcher(text);
      while (opening.find()) if (!inside(covered, opening.start())) unproven = true;
      var declaration = UNSUPPORTED.matcher(text);
      while (declaration.find()) {
        if (covered.stream().noneMatch(span -> span[0] < declaration.end() && span[1] > declaration.start()
            || span[0] >= declaration.end() && text.substring(declaration.end(), span[0]).isBlank())) {
          unproven = true;
        }
      }
    }
    var owned = candidates.stream().filter(candidate -> intent.elementRef().equals(candidate.owner())).toList();
    if (unproven || owned.size() > 1) {
      return new Resolution(null, null, "Whole source-code ownership is ambiguous or its declaration has an unsupported"
          + " boundary. Preserve the source and existing progress; do not classify it as generated code.");
    }
    if (owned.size() == 1) {
      var candidate = owned.getFirst();
      return new Resolution(new RequirementBodyBinding(Authority.VERBATIM_SOURCE_BODY, intent.elementRef(),
          "script", NativeBodyKind.GROOVY_SCRIPT, source.generation(), candidate.turn(), candidate.start(),
          candidate.end(), candidate.encoding()), candidate.body(), null);
    }
    boolean requestedBody = intent.requestedProperties() != null && intent.requestedProperties().stream()
        .anyMatch(property -> property != null && ownsBody(intent, property.key()));
    if (!requestedBody && intent.bodyBinding() == null) return new Resolution(null, null, null);
    if (source.inputs().isEmpty()) return new Resolution(null, null,
        "Retained author source is unavailable. Preserve the body and reclassify it when source evidence is available.");
    String turn = source.inputs().getFirst().sourceTurnId();
    return new Resolution(new RequirementBodyBinding(Authority.GENERATED_CANDIDATE, intent.elementRef(), "script",
        NativeBodyKind.GROOVY_SCRIPT, source.generation(), turn, null, null, null), null, null);
  }

  private static boolean inside(List<int[]> spans, int offset) {
    return spans.stream().anyMatch(span -> span[0] <= offset && offset < span[1]);
  }

  private static String prefix(String text, int offset) {
    int start = text.lastIndexOf('\n', Math.max(0, offset - 1)) + 1;
    if (text.substring(start, offset).isBlank() && start > 0) {
      int end = start - 1;
      start = text.lastIndexOf('\n', Math.max(0, end - 1)) + 1;
      return text.substring(start, end);
    }
    return text.substring(start, offset);
  }

  private static String owner(String prefix, List<RequirementElementIntent> owners) {
    var explicit = Pattern.compile("([A-Za-z0-9_-]+)\\.script\\s*[:=]").matcher(prefix);
    if (explicit.find()) {
      String ref = explicit.group(1);
      return owners.stream().filter(intent -> ref.equals(intent.elementRef()))
          .map(RequirementElementIntent::elementRef).findFirst().orElse(null);
    }
    var matches = owners.stream().filter(intent -> token(prefix, intent.elementRef())
        || intent.requestedName() != null && prefix.contains(intent.requestedName())).toList();
    if (matches.size() == 1) return matches.getFirst().elementRef();
    return matches.isEmpty() && owners.size() == 1 ? owners.getFirst().elementRef() : null;
  }

  private static boolean token(String text, String value) {
    return value != null && Pattern.compile("(?<![\\w-])" + Pattern.quote(value) + "(?![\\w-])")
        .matcher(text).find();
  }

  private static List<int[]> objectSpans(String text) {
    List<Integer> stack = new ArrayList<>();
    List<int[]> spans = new ArrayList<>();
    boolean quoted = false;
    boolean escaped = false;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (quoted) {
        if (escaped) escaped = false;
        else if (c == '\\') escaped = true;
        else if (c == '"') quoted = false;
      } else if (c == '"') quoted = true;
      else if (c == '{') stack.add(i);
      else if (c == '}' && !stack.isEmpty()) spans.add(new int[] {stack.removeLast(), i + 1});
    }
    return spans;
  }
}

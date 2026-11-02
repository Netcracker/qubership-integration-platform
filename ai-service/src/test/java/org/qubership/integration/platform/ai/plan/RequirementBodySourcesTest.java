package org.qubership.integration.platform.ai.plan;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.plan.RequirementBodyBinding.Authority;
import org.qubership.integration.platform.ai.plan.RequirementBodyBinding.Encoding;
import org.qubership.integration.platform.ai.plan.RequirementElementIntent.Placement;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;

class RequirementBodySourcesTest {
  @Test
  void completeFencedAndInlineCodePreserveEveryCharacter() {
    var intent = intent("prepare", null);
    for (String source : List.of("prepare.script:\n```groovy\n  return 'source'  \n\n```\n",
        "Use this exact code for prepare: `  return 'source'  `",
        "prepare.script:\n~~~groovy\n  return 'source'  \n~~~\n")) {
      var result = RequirementBodySources.resolve(intent, List.of(intent), packet(source));
      assertNull(result.error(), result.error());
      assertEquals(Authority.VERBATIM_SOURCE_BODY, result.binding().authority());
      assertEquals(Encoding.RAW, result.binding().encoding());
      assertEquals(result.body(), source.substring(result.binding().startOffset(), result.binding().endOffset()));
      assertTrue(result.body().startsWith("  return 'source'  "));
      assertEquals("source-generation", result.binding().sourceGeneration());
      assertEquals("source-turn", result.binding().sourceTurnId());
    }
  }

  @Test
  void explicitNativeConfigurationDecodesOnlyTheCompleteJsonStringValue() {
    String source;
    // Construct the value through the JSON writer so escaping and trailing whitespace are unambiguous.
    String body = "  return \"x\"\n ";
    try {
      source = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(java.util.Map.of(
          "id", "prepare", "type", "script", "properties", java.util.Map.of("script", body)));
    } catch (Exception unexpected) { throw new AssertionError(unexpected); }
    var intent = intent("prepare", "candidate");
    var result = RequirementBodySources.resolve(intent, List.of(intent), packet(source));
    assertNull(result.error(), result.error());
    assertEquals(body, result.body());
    assertEquals(Encoding.JSON_STRING, result.binding().encoding());
  }

  @Test
  void generatedCodeAndLanguageLabelsAreImplementationCandidatesWithoutSourceCode() {
    for (String candidate : List.of("Groovy", "return 'generated code'")) {
      var intent = intent("prepare", candidate);
      var result = RequirementBodySources.resolve(intent, List.of(intent),
          packet("Create a Groovy script that returns the requested result."));
      assertNull(result.error());
      assertEquals(Authority.GENERATED_CANDIDATE, result.binding().authority());
      assertNull(result.binding().startOffset());
      assertNull(result.body());
    }
  }

  @Test
  void declarationAndOwnerAmbiguityStayUnproven() {
    var prepare = intent("prepare", "candidate");
    var finish = intent("finish", null);
    for (String source : List.of("Use this exact script: return 'unbounded'",
        "script: return 'unbounded'", "```groovy\nreturn 'unclosed'", "```groovy\nreturn 'ambiguous'\n```\n",
        "prepare and finish script:\n```groovy\nreturn 'ambiguous'\n```\n",
        "foreign.script:\n```groovy\nreturn 'foreign'\n```\n",
        "{\"id\":\"foreign\",\"type\":\"script\",\"properties\":{\"script\":\"return 'foreign'\"}}")) {
      var result = RequirementBodySources.resolve(prepare, List.of(prepare, finish), packet(source));
      assertNotNull(result.error(), source);
      assertNull(result.binding(), source);
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {
      "After entry run exactly this script: exchange.message.body = 'receipt-confirmed'. Proceed without IDS.",
      "Use this exact script:\nreturn 'unfenced'",
      "Use exactly the following code: return 'unfenced'",
      "Run the verbatim following script: return 'unfenced'"
  })
  void anExplicitUnfencedBodyCannotBeDowngradedToGeneratedByItsQualifiers(String authorSource) {
    var intent = intent("prepare", "generated candidate");
    var result = RequirementBodySources.resolve(intent, List.of(intent), packet(authorSource));
    assertNotNull(result.error(), authorSource);
    assertNull(result.binding(), "An unsupported author-code boundary cannot confer generated authority.");
  }

  @Test
  void duplicateCodeValuesAndConflictingTurnsCannotChooseTheirOwnAuthority() {
    var intent = intent("prepare", "candidate");
    String first = "prepare.script:\n```groovy\nreturn 'first'\n```\n";
    String second = "prepare.script:\n```groovy\nreturn 'second'\n```\n";
    assertNotNull(RequirementBodySources.resolve(intent, List.of(intent), packet(first + second)).error());
    assertNotNull(RequirementBodySources.resolve(intent, List.of(intent), new AuthorSourceEvidence.Packet("gen",
        List.of(new AuthorSourceEvidence.Input("first", first), new AuthorSourceEvidence.Input("later", second)))).error());
  }

  @Test
  void unrelatedLaterClarificationRetainsGeneratedAuthorityButNewCodeOrGenerationCannotReuseIt() {
    var intent = intent("prepare", "generated hint");
    var original = new AuthorSourceEvidence.Input("original-turn", "Create a local Groovy script.");
    var before = RequirementBodySources.resolve(intent, List.of(intent),
        new AuthorSourceEvidence.Packet("generation", List.of(original)));
    var after = RequirementBodySources.resolve(intent, List.of(intent),
        new AuthorSourceEvidence.Packet("generation", List.of(original,
            new AuthorSourceEvidence.Input("later-turn", "Keep the same approved behavior."))));
    assertEquals(before.binding(), after.binding(), "An unrelated clarification cannot invalidate accepted body metadata.");
    var reset = RequirementBodySources.resolve(intent, List.of(intent),
        new AuthorSourceEvidence.Packet("new-generation", List.of(original)));
    assertNotEquals(before.binding(), reset.binding());
    var explicit = RequirementBodySources.resolve(intent, List.of(intent),
        new AuthorSourceEvidence.Packet("generation", List.of(original,
            new AuthorSourceEvidence.Input("literal-turn", "prepare.script:\n```groovy\nreturn 'author'\n```\n"))));
    assertEquals(Authority.VERBATIM_SOURCE_BODY, explicit.binding().authority());
    assertEquals("literal-turn", explicit.binding().sourceTurnId());
    assertNotEquals(before.binding(), explicit.binding());
  }

  @Test
  void explicitOwnersSeparateTwoBodiesAndMissingSourceNeverConfersGeneratedAuthority() {
    var first = intent("prepare", "candidate");
    var second = intent("finish", null);
    var source = packet("prepare.script:\n```groovy\nreturn 'one'\n```\n"
        + "finish.script:\n```groovy\nreturn 'two'\n```\n");
    assertEquals("return 'one'\n", RequirementBodySources.resolve(first, List.of(first, second), source).body());
    assertEquals("return 'two'\n", RequirementBodySources.resolve(second, List.of(first, second), source).body());
    assertNotNull(RequirementBodySources.resolve(first, List.of(first), new AuthorSourceEvidence.Packet(null, List.of())).error());
  }

  static RequirementElementIntent intent(String ref, String candidate) {
    return new RequirementElementIntent(ref, "script", Placement.LOCAL_NODE, "entry", null, "entry", null,
        candidate == null ? List.of() : List.of(new PlanProperty("script", candidate)), List.of());
  }

  static AuthorSourceEvidence.Packet packet(String source) {
    return new AuthorSourceEvidence.Packet("source-generation", List.of(new AuthorSourceEvidence.Input("source-turn", source)));
  }
}

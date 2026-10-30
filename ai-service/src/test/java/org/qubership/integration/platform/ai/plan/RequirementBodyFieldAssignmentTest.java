package org.qubership.integration.platform.ai.plan;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.qubership.integration.platform.ai.plan.RequirementBodyBinding.Authority;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;

class RequirementBodyFieldAssignmentTest {
  @ParameterizedTest
  @ValueSource(strings = {
      "Return an object with exactly code=\"DECLINED\", message=\"Cannot finish\".",
      "Return an object with exactly code = 'DECLINED', message = 'Cannot finish'.",
      "Return the result with exactly code=\"VALUE\", the applicable explanation, and message=\"Description\".",
      "Return the result with exactly code=\"A\\\"B\", message=\"Text\".",
      "Return the result with exactly code='A\\'B', message='Text'.",
      "Return an object WITH EXACTLY CODE = \"A\", message = \"B\".",
      "The result contains exactly code='A', detail='B'.",
      "Emit exactly code = \"A\", detail = \"B\".",
      "Define fields: exactly code='A', detail='B'.",
      "Retain detail='B', exactly code='A'.",
      "Retain detail = \"B\", exactly code = \"A\".",
      "The result contains exactly code\t=\t'A',\n detail='B'."
  })
  void quotedCodeFieldListsDoNotDeclareCompleteGroovyBodies(String text) {
    var first = intent("prepare");
    var second = intent("finish");
    for (var owner : List.of(first, second)) {
      var result = RequirementBodySources.resolve(owner, List.of(first, second), packet(text));
      assertNull(result.error(), result.error());
      assertEquals(Authority.GENERATED_CANDIDATE, result.binding().authority());
      assertNull(result.body());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "Use this exact code=return 'author'",
      "Use exact code=return 'author'",
      "Use exactly this code=return 'author'",
      "Use this exactly code='author', message='retained'",
      "Use exactly the following code='author', message='retained'",
      "Use exactly this code='author', message='retained'",
      "Use verbatim code='author', message='retained'",
      "Use the exact code='author', message='retained'",
      "Use exact code='author', message='retained'",
      "Use verbatim code=return 'author'",
      "Use this exact script=return 'author'",
      "Use exactly the following code: return 'author'",
      "Use this exact code: return 'author'",
      "Use the verbatim script body=return 'author'",
      "code body=return 'author'",
      "script=return 'author'",
      "prepare.script=return 'author'",
      "Return with exactly code=\"unterminated, message=\"X\".",
      "Return with exactly code=\"author\".",
      "Return with exactly code=runProgram(), message=\"X\".",
      "Retain message='Y'. Exactly code='X'.",
      "Exactly code='first\nsecond', message='X'.",
      "Retain message='Y'; exactly code='X'.",
      "Return exactly code='X', detail=runProgram().",
      "Return with exactly code=\"X\", message=\"Y\". Use this exact code=return 'author'",
      "Return with exactly code=\"X\", message=\"Y\".\nUse this exact script: return 'author'",
      "Return with exactly code=\"X\", message=\"Y\".\n```groovy\nreturn 'unclosed'"
  })
  void affirmativeOrAmbiguousWholeCodeDeclarationsRemainUnproven(String text) {
    var first = intent("prepare");
    var result = RequirementBodySources.resolve(first, List.of(first), packet(text));
    assertNotNull(result.error(), text);
    assertNull(result.binding(), text);
  }

  @Test
  void fieldListProseCannotHideOwnedWholeBodiesOrAmbiguousForeignBodies() {
    String fields = "Return with exactly code=\"X\", message=\"Y\".\n";
    var prepare = intent("prepare");
    var finish = intent("finish");
    String first = "prepare.script:\n```groovy\nreturn 'first'\n```\n";
    String second = "finish.script:\n```groovy\nreturn 'second'\n```\n";
    var owners = List.of(prepare, finish);
    for (var pair : List.of(new String[] {"prepare", "return 'first'\n"}, new String[] {"finish", "return 'second'\n"})) {
      var intent = owners.stream().filter(value -> pair[0].equals(value.elementRef())).findFirst().orElseThrow();
      var source = fields + first + second;
      var resolution = RequirementBodySources.resolve(intent, owners, packet(source));
      assertNull(resolution.error(), resolution.error());
      assertEquals(Authority.VERBATIM_SOURCE_BODY, resolution.binding().authority());
      assertEquals(pair[1], resolution.body());
      assertEquals(pair[1], source.substring(resolution.binding().startOffset(), resolution.binding().endOffset()));
    }
    for (String invalid : List.of(fields + first + first, fields + "foreign.script:\n```groovy\nreturn 'foreign'\n```\n")) {
      assertNotNull(RequirementBodySources.resolve(prepare, owners, packet(invalid)).error());
    }
  }

  private static RequirementElementIntent intent(String ref) {
    return new RequirementElementIntent(ref, "script", RequirementElementIntent.Placement.LOCAL_NODE, "entry",
        null, "entry", null, List.of(new PlanProperty("script", "generated hint")), List.of());
  }

  private static AuthorSourceEvidence.Packet packet(String text) {
    return new AuthorSourceEvidence.Packet("generation", List.of(new AuthorSourceEvidence.Input("turn", text)));
  }
}

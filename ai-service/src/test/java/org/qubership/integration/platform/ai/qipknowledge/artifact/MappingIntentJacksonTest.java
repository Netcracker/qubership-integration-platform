package org.qubership.integration.platform.ai.qipknowledge.artifact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.plan.RequirementBriefCapture;

/**
 * The generated LangChain4j argument mapper deserializes {@link MappingIntent} through Jackson.
 * Compact-constructor records with a secondary constructor used to throw
 * {@code InvalidDefinitionException}: no fallback setter for creator property {@code sourceRef}.
 */
class MappingIntentJacksonTest {

  private static final String INTENT_JSON =
      """
      {
        "mappingIntentId": "map-request",
        "sourceRef": "trigger-onTaskStart",
        "sourcePort": "output",
        "targetRef": "call-salesforce-createTask",
        "targetPort": "REQUEST",
        "rules": [{"sourcePath": "name", "targetPath": "Subject"}]
      }
      """;

  private static final String CAPTURE_JSON =
      """
      {
        "goal": "OM to Salesforce WFM",
        "summary": "Map request fields",
        "mappingIntents": [%s]
      }
      """
          .formatted(INTENT_JSON.strip());

  @Test
  void bindsMappingIntentFromToolJson() throws Exception {
    MappingIntent intent = mapper().readValue(INTENT_JSON, MappingIntent.class);

    assertEquals("trigger-onTaskStart", intent.sourceRef());
    assertEquals(MappingPort.OUTPUT, intent.sourcePort());
    assertEquals("call-salesforce-createTask", intent.targetRef());
    assertEquals("name", intent.rules().getFirst().sourcePath());
    assertEquals("Subject", intent.rules().getFirst().targetPath());
  }

  @Test
  void bindsNestedMappingIntentsOnRequirementBriefCapture() throws Exception {
    RequirementBriefCapture capture =
        mapper().readValue(CAPTURE_JSON, RequirementBriefCapture.class);

    assertEquals(1, capture.mappingIntents().size());
    assertEquals("trigger-onTaskStart", capture.mappingIntents().getFirst().sourceRef());
  }

  @Test
  void bindsCaptureThroughGeneratedMapperStyleWrapper() throws Exception {
    Wrapper wrapper =
        mapper()
            .readValue("{\"capture\":" + CAPTURE_JSON + "}", Wrapper.class);

    assertNotNull(wrapper.capture);
    assertEquals("map-request", wrapper.capture.mappingIntents().getFirst().mappingIntentId());
    assertEquals("trigger-onTaskStart", wrapper.capture.mappingIntents().getFirst().sourceRef());
  }

  /**
   * Matches {@code QuarkusJsonCodecFactory.ObjectMapperHolder}: tool JSON is bound with field
   * visibility only. Getters are hidden, which is what made MappingIntent fail to deserialize.
   */
  private static ObjectMapper mapper() {
    return JsonMapper.builder()
        .visibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.NONE)
        .visibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY)
        .disable(MapperFeature.ALLOW_FINAL_FIELDS_AS_MUTATORS)
        .build();
  }

  public static final class Wrapper {
    public RequirementBriefCapture capture;
  }
}

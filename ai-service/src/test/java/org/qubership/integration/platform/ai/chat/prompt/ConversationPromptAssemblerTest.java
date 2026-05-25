package org.qubership.integration.platform.ai.chat.prompt;

import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.chainplan.ActiveChainPlanService;
import org.qubership.integration.platform.ai.chat.model.ChatRequest;
import org.qubership.integration.platform.ai.chat.planning.ConversationPlanningDiaryService;
import org.qubership.integration.platform.ai.integration.apihub.ApiHubRuntimePromptNote;
import org.qubership.integration.platform.ai.rag.RagRetriever;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationPromptAssemblerTest {

  @Test
  void planningProfileAuthoritativeBeforeUserBlock() throws Exception {
    ConversationPromptAssembler assembler = new ConversationPromptAssembler();
    AuthoritativeStateSection auth = mock(AuthoritativeStateSection.class);
    when(auth.format("conv-1")).thenReturn("## Authoritative state\n\n- scenario phase: COLD\n");
    inject(assembler, "authoritativeStateSection", auth);
    inject(assembler, "planningDiaryService", mock(ConversationPlanningDiaryService.class));
    inject(assembler, "activeChainPlanService", mock(ActiveChainPlanService.class));
    ApiHubRuntimePromptNote apiHub = mock(ApiHubRuntimePromptNote.class);
    when(apiHub.maybePrefix(anyString())).thenAnswer(inv -> inv.getArgument(0));
    inject(assembler, "apiHubRuntimePromptNote", apiHub);
    inject(assembler, "ragRetriever", mock(RagRetriever.class));

    ChatRequest request = new ChatRequest();
    request.setMessage("build http forward chain");

    String body =
        assembler.assembleBody(PromptAssemblyRequest.of("conv-1", request, PromptProfile.PLANNING));

    int authIdx = body.indexOf("## Authoritative state");
    int userIdx = body.indexOf("## User Request");
    assertTrue(authIdx >= 0);
    assertTrue(userIdx > authIdx);
  }

  @Test
  void planningProfileRagQueryUsesEffectiveTextHead() throws Exception {
    RagRetriever ragRetriever = mock(RagRetriever.class);
    when(ragRetriever.retrieve(anyString(), any(), any(), anyInt())).thenReturn(List.of());
    when(ragRetriever.toContextString(any())).thenReturn("");

    ConversationPromptAssembler assembler = new ConversationPromptAssembler();
    inject(assembler, "ragRetriever", ragRetriever);
    inject(assembler, "authoritativeStateSection", mock(AuthoritativeStateSection.class));
    inject(assembler, "planningDiaryService", mock(ConversationPlanningDiaryService.class));
    inject(assembler, "activeChainPlanService", mock(ActiveChainPlanService.class));
    ApiHubRuntimePromptNote apiHub = mock(ApiHubRuntimePromptNote.class);
    when(apiHub.maybePrefix(anyString())).thenAnswer(inv -> inv.getArgument(0));
    inject(assembler, "apiHubRuntimePromptNote", apiHub);

    ChatRequest request = new ChatRequest();
    request.setMessage("Create chain");
    request.setResolvedEffectiveUserText(
        "Create chain\n\n---\n\n# Integration Design Specification (IDS)\n");

    assembler.assemble(PromptAssemblyRequest.of("conv-rag", request, PromptProfile.PLANNING));

    verify(ragRetriever).retrieve(eq("Create chain"), eq("schema"), isNull(), eq(4));
  }

  @Test
  void minimalProfileAssembleEscapesEffectiveTextOnly() throws Exception {
    ConversationPromptAssembler assembler = new ConversationPromptAssembler();
    inject(assembler, "ragRetriever", mock(RagRetriever.class));

    ChatRequest request = new ChatRequest();
    request.setMessage("patch element");

    String out =
        assembler.assemble(PromptAssemblyRequest.of("conv-2", request, PromptProfile.MINIMAL));

    assertFalse(out.contains("## Authoritative state"));
    assertTrue(out.contains("patch element"));
  }

  private static void inject(Object target, String fieldName, Object value) throws Exception {
    Field f = target.getClass().getDeclaredField(fieldName);
    f.setAccessible(true);
    f.set(target, value);
  }
}

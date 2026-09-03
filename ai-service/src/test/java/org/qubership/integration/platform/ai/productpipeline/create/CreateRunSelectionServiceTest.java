package org.qubership.integration.platform.ai.productpipeline.create;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.productpipeline.artifact.CompilerRunPin;
import org.qubership.integration.platform.ai.productpipeline.artifact.ResolvedCompilerDag;
import org.qubership.integration.platform.ai.productpipeline.knowledge.FakeKnowledgeClient;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgeContextProvider;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfile;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfileCatalog;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfileParser;

class CreateRunSelectionServiceTest {

  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-07-22T12:00:00Z"), ZoneOffset.UTC);

  private InMemoryArtifactBlobStore blobs;
  private FakeKnowledgeClient knowledge;
  private ObjectMapper mapper;
  private ProductPipelineProfile createChainProfile;
  private ProductPipelineProfile createChainV2Profile;
  private CompilerRunPinResolver pinResolver;
  private CompilerRunPin stubPin;

  @BeforeEach
  void setUp() throws Exception {
    blobs = new InMemoryArtifactBlobStore();
    knowledge = FakeKnowledgeClient.defaultFixture();
    mapper = new ObjectMapper();
    try (InputStream in =
        getClass().getResourceAsStream("/product-pipelines/profiles/create-chain-v1.yaml")) {
      createChainProfile = ProductPipelineProfileParser.parse(in);
    }
    try (InputStream in =
        getClass().getResourceAsStream("/product-pipelines/profiles/create-chain-v2.yaml")) {
      createChainV2Profile = ProductPipelineProfileParser.parse(in);
    }
    stubPin =
        new CompilerRunPin(
            "pkg",
            "1",
            "digest",
            1,
            "idx-1",
            "idx-digest",
            new ResolvedCompilerDag(List.of(), List.of(), "dag"),
            List.of("planning"),
            Map.of(),
            Map.of("skill", "a".repeat(64)),
            List.of(),
            null,
            null,
            null,
            null,
            null,
            null);
    pinResolver = mock(CompilerRunPinResolver.class);
    when(pinResolver.resolve(any(), any())).thenReturn(stubPin);
  }

  @Test
  void newConversationAlwaysPinsCreateChainV2() {
    CreateRunSelectionService service = service(catalogWithCreateChain(), pinResolver, knowledge);
    var selection = service.selectOrCreate("conv-new");
    assertNotNull(selection.runManifest());
    assertEquals("create-chain", selection.runManifest().profileId());
    assertEquals("2", selection.runManifest().profileVersion());
    assertEquals("conv-new-create-chain-2", selection.productRunId());
    assertEquals(stubPin, selection.runManifest().compilerRunPin());
    assertEquals(
        knowledge.context().packageRef(), selection.runManifest().knowledgePackage());
  }

  @Test
  void missingCreateChainProfileFailsClosed() {
    CreateRunSelectionService service =
        service(new ProductPipelineProfileCatalog(List.of()), pinResolver, knowledge);
    assertThrows(IllegalArgumentException.class, () -> service.selectOrCreate("conv-missing-profile"));
  }

  @Test
  void missingCompilerPinResolverFailsClosed() {
    CompilerRunPinResolver failing = mock(CompilerRunPinResolver.class);
    when(failing.resolve(any(), any()))
        .thenThrow(new IllegalStateException("compiler pin unavailable"));
    CreateRunSelectionService service = service(catalogWithCreateChain(), failing, knowledge);
    assertThrows(IllegalStateException.class, () -> service.selectOrCreate("conv-missing-pin"));
  }

  @Test
  void missingKnowledgePackageFailsClosed() {
    KnowledgeContextProvider blankKnowledge =
        conversationId -> {
          throw new IllegalStateException("knowledge package unavailable");
        };
    CreateRunSelectionService service =
        new CreateRunSelectionService(
            "2026.1",
            blankKnowledge,
            new CreateRunBindingStore(blobs, mapper),
            catalogWithCreateChain(),
            pinResolver,
            CLOCK);
    IllegalStateException thrown =
        assertThrows(IllegalStateException.class, () -> service.selectOrCreate("conv-no-package"));
    assertTrue(thrown.getMessage().contains("knowledge package"));
  }

  @Test
  void existingCreateChainBindingIgnoresChangedStartupEnvironment() {
    CreateRunSelectionService first =
        service(catalogWithCreateChain(), pinResolver, knowledge, "2026.1");
    var original = first.selectOrCreate("conv-stable");

    CreateRunSelectionService restarted =
        service(catalogWithCreateChain(), pinResolver, knowledge, "2099.9");
    var resumed = restarted.selectOrCreate("conv-stable");
    assertEquals(original.runManifest(), resumed.runManifest());
    assertEquals(original.productRunId(), resumed.productRunId());
    assertEquals("2026.1", resumed.runManifest().languageVersion());
  }

  @Test
  void firstPromptPinsResponseLocaleForTheConversation() {
    AtomicInteger classifications = new AtomicInteger();
    ResponseLocaleResolver localeResolver =
        new ResponseLocaleResolver(
            (java.util.function.Function<String, String>)
                prompt -> {
              classifications.incrementAndGet();
              return prompt.contains("Create") ? "en" : "fr";
            });
    CreateRunSelectionService service =
        service(catalogWithCreateChain(), pinResolver, knowledge, "2026.1", localeResolver);

    var original = service.selectOrCreate("conv-locale", "Create an integration chain");
    var resumed = service.selectOrCreate("conv-locale", "Approve the requirements");

    assertEquals("en", original.runManifest().responseLocale());
    assertEquals("en", resumed.runManifest().responseLocale());
    assertEquals(1, classifications.get());
  }

  @Test
  void applicationPropertiesDoNotExposeRuntimeOrProfileSelectors() throws Exception {
    String props =
        Files.readString(
            Path.of("src/main/resources/application.properties"));
    assertFalse(props.contains("qip.ai.create.runtime"));
    assertFalse(props.contains("qip.ai.create.product-profile-id"));
    assertFalse(props.contains("qip.ai.create.product-profile-version"));
    assertFalse(props.contains("QIP_CREATE_RUNTIME"));
    assertFalse(props.contains("QIP_CREATE_PRODUCT_PROFILE_ID"));
    assertFalse(props.contains("QIP_CREATE_PRODUCT_PROFILE_VERSION"));
    assertTrue(props.contains("qip.ai.create.language-version"));
  }

  @Test
  void composeDoesNotExposeCreateRuntimeOrProfileSelectors() throws Exception {
    String compose =
        Files.readString(Path.of("../infrastructure/docker-compose.yml"));
    assertFalse(compose.contains("QIP_CREATE_RUNTIME"));
    assertFalse(compose.contains("QIP_CREATE_PRODUCT_PROFILE_ID"));
    assertFalse(compose.contains("QIP_CREATE_PRODUCT_PROFILE_VERSION"));
  }

  private ProductPipelineProfileCatalog catalogWithCreateChain() {
    return new ProductPipelineProfileCatalog(List.of(createChainProfile, createChainV2Profile));
  }

  private CreateRunSelectionService service(
      ProductPipelineProfileCatalog catalog,
      CompilerRunPinResolver resolver,
      FakeKnowledgeClient knowledgeClient) {
    return service(catalog, resolver, knowledgeClient, "2026.1");
  }

  private CreateRunSelectionService service(
      ProductPipelineProfileCatalog catalog,
      CompilerRunPinResolver resolver,
      FakeKnowledgeClient knowledgeClient,
      String languageVersion) {
    return new CreateRunSelectionService(
        languageVersion,
        knowledgeClient,
        new CreateRunBindingStore(blobs, mapper),
        catalog,
        resolver,
        CLOCK);
  }

  private CreateRunSelectionService service(
      ProductPipelineProfileCatalog catalog,
      CompilerRunPinResolver resolver,
      FakeKnowledgeClient knowledgeClient,
      String languageVersion,
      ResponseLocaleResolver responseLocaleResolver) {
    return new CreateRunSelectionService(
        languageVersion,
        knowledgeClient,
        new CreateRunBindingStore(blobs, mapper),
        catalog,
        resolver,
        CLOCK,
        CreateRunSelectionService.CREATE_PROFILE_VERSION,
        responseLocaleResolver);
  }
}

package org.qubership.integration.platform.ai.productpipeline.create;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.qubership.integration.platform.ai.productpipeline.artifact.CompilerRunPin;
import org.qubership.integration.platform.ai.productpipeline.artifact.RunManifest;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgeContextProvider;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgePackageRef;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgeQueryContext;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfile;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfileCatalog;

/**
 * Pins CREATE to product create-chain@2 once per conversation for new runs. Existing supported
 * bindings (create-chain@1 or @2) are immutable across restarts; unsupported historical bindings
 * fail closed.
 */
@ApplicationScoped
public class CreateRunSelectionService {

  public static final String CREATE_PROFILE_ID = "create-chain";
  public static final String CREATE_PROFILE_VERSION = "2";

  private final String languageVersion;
  private final KnowledgeContextProvider knowledgeContextProvider;
  private final CreateRunBindingStore bindingStore;
  private final ProductPipelineProfileCatalog profileCatalog;
  private final CompilerRunPinResolver compilerRunPinResolver;
  private final Clock clock;
  private final String createProfileVersion;
  private final ResponseLocaleResolver responseLocaleResolver;

  @Inject
  public CreateRunSelectionService(
      @ConfigProperty(name = "qip.ai.create.language-version", defaultValue = "2026.1")
          String languageVersion,
      KnowledgeContextProvider knowledgeContextProvider,
      CreateRunBindingStore bindingStore,
      ProductPipelineProfileCatalog profileCatalog,
      CompilerRunPinResolver compilerRunPinResolver,
      ResponseLocaleResolver responseLocaleResolver) {
    this(
        languageVersion,
        knowledgeContextProvider,
        bindingStore,
        profileCatalog,
        compilerRunPinResolver,
        Clock.systemUTC(),
        CREATE_PROFILE_VERSION,
        responseLocaleResolver);
  }

  /** Test constructor that pins new runs to {@link #CREATE_PROFILE_VERSION}. */
  public CreateRunSelectionService(
      String languageVersion,
      KnowledgeContextProvider knowledgeContextProvider,
      CreateRunBindingStore bindingStore,
      ProductPipelineProfileCatalog profileCatalog,
      CompilerRunPinResolver compilerRunPinResolver,
      Clock clock) {
    this(
        languageVersion,
        knowledgeContextProvider,
        bindingStore,
        profileCatalog,
        compilerRunPinResolver,
        clock,
        CREATE_PROFILE_VERSION,
        new ResponseLocaleResolver(
            (java.util.function.Function<String, String>)
                prompt -> ResponseLocaleResolver.DEFAULT_LOCALE));
  }

  /**
   * Test constructor that pins new runs to an explicit profile version. Use {@code "1"} for
   * fixtures that still exercise the create-chain@1 capability graph.
   */
  public CreateRunSelectionService(
      String languageVersion,
      KnowledgeContextProvider knowledgeContextProvider,
      CreateRunBindingStore bindingStore,
      ProductPipelineProfileCatalog profileCatalog,
      CompilerRunPinResolver compilerRunPinResolver,
      Clock clock,
      String createProfileVersion) {
    this(
        languageVersion,
        knowledgeContextProvider,
        bindingStore,
        profileCatalog,
        compilerRunPinResolver,
        clock,
        createProfileVersion,
        new ResponseLocaleResolver(
            (java.util.function.Function<String, String>)
                prompt -> ResponseLocaleResolver.DEFAULT_LOCALE));
  }

  CreateRunSelectionService(
      String languageVersion,
      KnowledgeContextProvider knowledgeContextProvider,
      CreateRunBindingStore bindingStore,
      ProductPipelineProfileCatalog profileCatalog,
      CompilerRunPinResolver compilerRunPinResolver,
      Clock clock,
      String createProfileVersion,
      ResponseLocaleResolver responseLocaleResolver) {
    this.languageVersion = Objects.requireNonNull(languageVersion, "languageVersion");
    this.knowledgeContextProvider =
        Objects.requireNonNull(knowledgeContextProvider, "knowledgeContextProvider");
    this.bindingStore = Objects.requireNonNull(bindingStore, "bindingStore");
    this.profileCatalog = Objects.requireNonNull(profileCatalog, "profileCatalog");
    this.compilerRunPinResolver =
        Objects.requireNonNull(compilerRunPinResolver, "compilerRunPinResolver");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.createProfileVersion =
        Objects.requireNonNull(createProfileVersion, "createProfileVersion");
    this.responseLocaleResolver =
        Objects.requireNonNull(responseLocaleResolver, "responseLocaleResolver");
  }

  public Optional<CreateRunSelection> existing(String conversationId) {
    return bindingStore.load(conversationId).map(this::toSelection);
  }

  public CreateRunSelection selectOrCreate(String conversationId) {
    return selectOrCreate(conversationId, "");
  }

  public CreateRunSelection selectOrCreate(String conversationId, String firstPrompt) {
    Objects.requireNonNull(conversationId, "conversationId");
    return bindingStore
        .load(conversationId)
        .map(this::toSelection)
        .orElseGet(
            () -> toSelection(bindingStore.create(newBinding(conversationId, firstPrompt))));
  }

  private CreateRunBinding newBinding(String conversationId, String firstPrompt) {
    Instant createdAt = clock.instant();
    KnowledgeQueryContext knowledge = knowledgeContextProvider.forConversation(conversationId);
    KnowledgePackageRef packageRef = knowledge.packageRef();
    if (packageRef == null) {
      throw new IllegalStateException(
          "product CREATE requires a knowledge package from KnowledgeContextProvider");
    }
    ProductPipelineProfile profile =
        profileCatalog.require(CREATE_PROFILE_ID, createProfileVersion);
    if (profile.compilerPipeline() == null) {
      throw new IllegalStateException(
          "create-chain@"
              + createProfileVersion
              + " requires a compilerPipeline declaration in the profile catalog");
    }
    CompilerRunPin compilerRunPin = compilerRunPinResolver.resolve(profile, knowledge);
    if (compilerRunPin == null) {
      throw new IllegalStateException(
          "create-chain@" + createProfileVersion + " requires a non-null compiler run pin");
    }
    String productRunId = conversationId + "-" + CREATE_PROFILE_ID + "-" + createProfileVersion;
    RunManifest manifest =
        new RunManifest(
            productRunId,
            null,
            List.of(),
            "product",
            CREATE_PROFILE_ID,
            createProfileVersion,
            CREATE_PROFILE_ID + "@" + createProfileVersion,
            "reference-baseline-v1",
            "reference-baseline-v1",
            List.of(),
            "closure",
            packageRef,
            languageVersion,
            List.of(),
            compilerRunPin,
            responseLocaleResolver.resolve(firstPrompt));
    return new CreateRunBinding(conversationId, productRunId, manifest, createdAt);
  }

  private CreateRunSelection toSelection(CreateRunBinding binding) {
    return new CreateRunSelection(binding.runManifest(), binding.productRunId());
  }

  public record CreateRunSelection(RunManifest runManifest, String productRunId) {}
}

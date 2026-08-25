package org.qubership.integration.platform.ai.configuration;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

@ConfigMapping(prefix = "qip.ai")
public interface AppConfig {

  @WithName("apihub")
  ApihubConfig apihub();

  @WithName("storage")
  StorageConfig storage();

  @WithName("catalog")
  CatalogConfig catalog();

  @WithName("trace")
  TraceConfig trace();

  @WithName("capture")
  CaptureConfig capture();

  @WithName("llm")
  LlmConfig llm();

  @WithName("pattern-selector")
  PatternSelectorConfig patternSelector();

  @WithName("compiler-skill")
  CompilerSkillConfig compilerSkill();

  @WithName("create")
  CreateConfig create();

  /**
   * Runtime A2A rollout flag. Default false. When false, A2A discovery and invocation return a
   * deliberate disabled response; browser chat and persisted Task rows stay intact.
   */
  @WithName("a2a")
  A2aConfig a2a();

  /**
   * Maven build-time properties under {@code qip.ai.qipknowledge.*} may appear as system
   * properties during the test JVM. Map the known skip flag so ConfigMapping validation stays
   * fail-closed for other unknown keys.
   */
  @WithName("qipknowledge")
  QipKnowledgeConfig qipknowledge();

  @WithName("e2e")
  E2eConfig e2e();

  interface A2aConfig {
    @WithDefault("false")
    boolean enabled();

    @WithName("default-tenant-id")
    @WithDefault("local")
    String defaultTenantId();

    @WithName("default-subject-id")
    @WithDefault("local-user")
    String defaultSubjectId();

    /**
     * Public base URL advertised on the Agent Card. Empty falls back to {@code
     * http://localhost:<quarkus.http.port>}.
     */
    @WithName("public-base-url")
    @WithDefault("")
    String publicBaseUrl();

    /**
     * Logs the JSON-RPC request body exactly as it arrived, before deserialization.
     *
     * <p>Off by default. The body carries the caller's own text, which the launch observability
     * rules keep out of logs, so this opens a deliberate debugging window rather than steady-state
     * telemetry. Turn it on when a field may not be where the service looks for it: every other log
     * reports fields the service already understood and cannot show one that arrived elsewhere.
     */
    @WithName("log-inbound-payload")
    @WithDefault("false")
    boolean logInboundPayload();

    /**
     * How long the conversational skill waits for a turn before answering with the Task id.
     *
     * <p>A peer that expects one round trip cannot wait out a design or materialization stage. The
     * default stays well under the 180-second client timeout DCA uses, and the run keeps going
     * after the answer is sent — the caller reaches it again through the same {@code contextId}.
     */
    @WithName("assist-turn-budget")
    @WithDefault("PT45S")
    java.time.Duration assistTurnBudget();

    /**
     * Exclusive dispatch lease duration. Renewed while facade work is in progress so long LLM or
     * materialization runs do not lose ownership to a concurrent retry.
     */
    @WithName("dispatch-lease")
    @WithDefault("PT5M")
    java.time.Duration dispatchLease();

    /**
     * Independent heartbeat interval for dispatch lease renewal. Must be strictly less than
     * {@link #dispatchLease()}.
     */
    @WithName("dispatch-heartbeat-interval")
    @WithDefault("PT30S")
    java.time.Duration dispatchHeartbeatInterval();

    /**
     * Fixed worker count for lease renewals. Renewals are short JDBC updates and at most one runs
     * per dispatch, so a small pool covers the single-replica MVP. A rejected renewal is treated as
     * lost ownership rather than queued.
     */
    @WithName("dispatch-renew-workers")
    @WithDefault("4")
    int dispatchRenewWorkers();
  }

  interface CreateConfig {
    @WithName("language-version")
    @WithDefault("2026.1")
    String languageVersion();

    /**
     * Create-chain Flow rollout flag. Default false. When true, the provided-IDS route uses Quarkus
     * Flow through design planning.
     */
    @WithName("flow")
    FlowConfig flow();

    /** Bounds the model calls a run spends explaining its halts. */
    @WithName("failure-narrative")
    FailureNarrativeConfig failureNarrative();

    @WithName("run-cache-idle-timeout")
    @WithDefault("PT1H")
    java.time.Duration runCacheIdleTimeout();

    @WithName("flow-cache-idle-timeout")
    @WithDefault("PT1H")
    java.time.Duration flowCacheIdleTimeout();

    interface FlowConfig {
      @WithDefault("false")
      boolean enabled();
    }

    interface FailureNarrativeConfig {
      /**
       * Wall-clock bound on one narrative or owner-diagnosis turn. A turn that outlives it is
       * abandoned and the halt card carries the raw evidence instead.
       */
      @WithName("timeout")
      @WithDefault("PT20S")
      java.time.Duration timeout();

      /**
       * Model calls one run may spend on halt narration, counted across every halt it reaches. Once
       * spent, later halts carry the raw evidence and keep their actions.
       */
      @WithName("max-calls-per-run")
      @WithDefault("12")
      int maxCallsPerRun();
    }
  }

  interface QipKnowledgeConfig {
    @WithName("build")
    QipKnowledgeBuildConfig build();

    interface QipKnowledgeBuildConfig {
      @WithDefault("false")
      boolean skip();
    }
  }

  interface E2eConfig {
    // Empty String would collide with SmallRye's built-in String converter (SRCFG00040 treats
    // "" as null for a plain String); Optional is the documented way around it.
    @WithName("recovery-fault-chain-prefix")
    java.util.Optional<String> recoveryFaultChainPrefix();
  }

  interface ApihubConfig {
    @WithName("base-url")
    @WithDefault("http://apihub-mcp:3000/mcp/")
    String baseUrl();

    @WithName("probe-on-startup")
    @WithDefault("false")
    boolean probeOnStartup();
  }

  interface StorageConfig {
    @WithName("bucket-name")
    @WithDefault("qip-ai-storage")
    String bucketName();

    @WithName("initialize-bucket-on-startup")
    @WithDefault("false")
    boolean initializeBucketOnStartup();
  }

  interface CatalogConfig {
    @WithName("log-response-body")
    @WithDefault("true")
    boolean logResponseBody();
  }

  interface TraceConfig {
    @WithName("log-assistant-result")
    @WithDefault("false")
    boolean logAssistantResult();

    @WithName("assistant-result-max-chars")
    @WithDefault("8000")
    int assistantResultMaxChars();

    @WithName("log-tools")
    @WithDefault("true")
    boolean logTools();
  }

  interface CaptureConfig {
    @WithName("max-repair-attempts")
    @WithDefault("2")
    int maxRepairAttempts();

    @WithName("feedback-cache-idle-timeout")
    @WithDefault("PT1H")
    java.time.Duration feedbackCacheIdleTimeout();
  }

  interface PatternSelectorConfig {
    @WithName("max-golden-pattern-calls")
    @WithDefault("8")
    int maxGoldenPatternCalls();

    @WithName("max-decision-node-calls")
    @WithDefault("4")
    int maxDecisionNodeCalls();
  }

  interface CompilerSkillConfig {
    @WithName("max-golden-pattern-calls")
    @WithDefault("4")
    int maxGoldenPatternCalls();

    @WithName("max-decision-node-calls")
    @WithDefault("3")
    int maxDecisionNodeCalls();

    @WithName("max-generator-contract-calls")
    @WithDefault("4")
    int maxGeneratorContractCalls();

    @WithName("max-validation-rule-calls")
    @WithDefault("6")
    int maxValidationRuleCalls();

    @WithName("max-rule-calls")
    @WithDefault("4")
    int maxRuleCalls();
  }

  interface LlmConfig {
    @WithName("exchange")
    ExchangeConfig exchange();

    @WithName("rate-limit")
    RateLimitConfig rateLimit();

    interface RateLimitConfig {
      @WithDefault("true")
      boolean enabled();

      @WithName("max-attempts")
      @WithDefault("3")
      int maxAttempts();

      /** Max {@code llm:rate-limit-backoff} waits per chat turn before failing closed. */
      @WithName("max-turn-backoffs")
      @WithDefault("12")
      int maxTurnBackoffs();
    }

    interface ExchangeConfig {
      @WithDefault("false")
      boolean enabled();

      @WithName("max-chars")
      @WithDefault("50000")
      int maxChars();
    }
  }
}

package org.qubership.integration.platform.ai.configuration;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

import java.util.Optional;

/**
 * Typed configuration mapping for all QIP AI service properties. All sensitive values (keys,
 * passwords) are provided via environment variables.
 */
@ConfigMapping(prefix = "qip.ai")
public interface AppConfig {

  @WithName("apihub")
  ApihubConfig apihub();

  @WithName("catalog")
  CatalogConfig catalog();

  @WithName("hitl")
  HitlConfig hitl();

  @WithName("rag")
  RagConfig rag();

  @WithName("conversation")
  ConversationConfig conversation();

  @WithName("storage")
  StorageConfig storage();

  @WithName("trace")
  TraceConfig trace();

  @WithName("chain-plan")
  ChainPlanConfig chainPlan();

  @WithName("router")
  RouterConfig router();

  @WithName("guardrail")
  GuardrailConfig guardrail();

  @WithName("schema")
  SchemaConfig schema();

  interface ApihubConfig {
    @WithName("base-url")
    @WithDefault("http://apihub-mcp:3000/mcp/")
    String baseUrl();

    /**
     * When true, logs one INFO line after startup with MCP {@code initialize} + {@code tools/list}
     * outcome and remote tool names (does not log api-key).
     */
    @WithName("probe-on-startup")
    @WithDefault("false")
    boolean probeOnStartup();
  }

  interface CatalogConfig {
    @WithName("base-url")
    @WithDefault("http://cloud-integration-platform-catalog:8080")
    String baseUrl();

    @WithName("token")
    Optional<String> token();

    /** When true, log truncated catalog HTTP response bodies at INFO. */
    @WithName("log-response-body")
    @WithDefault("false")
    boolean logResponseBody();
  }

  interface HitlConfig {
    @WithName("timeout-seconds")
    @WithDefault("300")
    long timeoutSeconds();
  }

  interface RagConfig {
    @WithName("embedding-encoding-format")
    @WithDefault("float")
    String embeddingEncodingFormat();

    @WithName("chunk-size")
    @WithDefault("800")
    int chunkSize();

    @WithName("chunk-overlap")
    @WithDefault("100")
    int chunkOverlap();
  }

  interface ConversationConfig {
    @WithName("max-messages")
    @WithDefault("100")
    int maxMessages();
  }

  interface StorageConfig {
    @WithName("bucket-name")
    @WithDefault("qip-ai-storage")
    String bucketName();

    /**
     * When true, verifies or creates the bucket on application startup (requires a reachable
     * S3/MinIO). Keep false in environments where MinIO is optional or starts after the AI service.
     */
    @WithName("initialize-bucket-on-startup")
    @WithDefault("false")
    boolean initializeBucketOnStartup();
  }

  /** Optional tracing for debugging streaming agents without per-chunk LLM response logs. */
  interface TraceConfig {
    /** Log one INFO line with aggregated assistant text after the token stream completes. */
    @WithName("log-assistant-result")
    @WithDefault("false")
    boolean logAssistantResult();

    /** Max characters for the aggregated assistant log preview (truncate with ellipsis). */
    @WithName("assistant-result-max-chars")
    @WithDefault("8000")
    int assistantResultMaxChars();
  }

  interface ChainPlanConfig {
    @WithName("prompt-max-chars")
    @WithDefault("6000")
    int promptMaxChars();

    @WithName("archive-max")
    @WithDefault("5")
    int archiveMax();

    @WithName("llm-approval.enabled")
    @WithDefault("true")
    boolean llmApprovalEnabled();
  }

  interface GuardrailConfig {
    @WithName("transcript.max-messages")
    @WithDefault("10")
    int transcriptMaxMessages();

    @WithName("transcript.max-chars-per-message")
    @WithDefault("1500")
    int transcriptMaxCharsPerMessage();

    @WithName("transcript.max-total-chars")
    @WithDefault("14000")
    int transcriptMaxTotalChars();
  }

  interface RouterConfig {
    @WithName("embedding")
    RouterEmbeddingConfig embedding();

    @WithName("transcript.max-messages")
    @WithDefault("10")
    int transcriptMaxMessages();

    @WithName("transcript.max-chars-per-message")
    @WithDefault("1200")
    int transcriptMaxCharsPerMessage();

    @WithName("transcript.max-total-chars")
    @WithDefault("12000")
    int transcriptMaxTotalChars();
  }

  interface SchemaConfig {
    interface ValidationConfig {
      /** {@code legacy} (custom validator) or {@code networknt}. */
      @WithName("engine")
      @WithDefault("legacy")
      String engine();

      /** Comma-separated element types for networknt pilot when engine is {@code networknt}. */
      @WithName("networknt.element-types")
      @WithDefault("service-call,http-trigger")
      String networkntElementTypes();
    }

    @WithName("validation")
    ValidationConfig validation();
  }

  interface RouterEmbeddingConfig {
    @WithDefault("true")
    boolean enabled();

    @WithName("min-score")
    @WithDefault("0.72")
    double minScore();

    @WithName("min-margin")
    @WithDefault("0.04")
    double minMargin();

    @WithName("max-results")
    @WithDefault("5")
    int maxResults();
  }
}

export type ChatRole = "system" | "user" | "assistant";

/** Display-only failure marker; never sent to the AI service. */
export type ChatMessageVariant = "error";

export interface ActivityStepPayload {
  id: string;
  kind: "skill" | "pipeline" | "tool";
  status: "running" | "completed" | "error" | "cancelled";
  label?: string;
  parentId?: string | null;
}

/** UI-only activity snapshot persisted with the assistant turn (not sent to the API). */
export interface PersistedActivitySnapshot {
  steps: ActivityStepPayload[];
  summary: string;
  collapsed: boolean;
}

/** Server-owned decision (approval gate or clarification) rendered as a card in the transcript. */
export interface ChatDecision {
  /** Stable identity of the gate: reuse the server's value, never generate one client-side. */
  id: string;
  kind: "approve" | "clarify";
  /** Server-authored question, already in the language of the conversation. */
  question: string;
  /** Approval binding. Present when kind === "approve". */
  artifactType?: string;
  artifactHash?: string;
  revision?: number;
  /** Clarification detail. Present when kind === "clarify". */
  reason?: string;
  missingEvidence?: string[];
  /** Actions the gate accepts, in display order, e.g. ["approve", "request-changes"]. */
  actions: string[];
  /** Contextual create-chain recovery authored and routed by the server. */
  recovery?: {
    category:
      | "temporary-technical-failure"
      | "regeneratable-execution-failure"
      | "requirement-brief-defect"
      | "plan-artifact-defect"
      | "permanent-environment-failure"
      | "internal-service-failure"
      | "repeated-identical-failure"
      | "unclassified-failure";
    title: string;
    summary: string;
    preservedWork: string;
    technicalDetails: string;
    retryDelayMs?: number;
    runId?: string;
    failedStageId?: string;
  };
  /** Set once the reader answered; the card then renders frozen. */
  answeredAction?: string;
}

export interface ChatMessage {
  id?: string;
  role: ChatRole;
  content: string;
  /** When set to "error", the bubble is a turn-failure notice for the UI only. */
  variant?: ChatMessageVariant;
  /** Technical detail shown under the user-facing summary for error variants. */
  detail?: string;
  /** Collapsed activity for completed assistant turns (local session storage). */
  activity?: PersistedActivitySnapshot;
  /** Pending or answered decision gate riding on this message (UI-side data, not sent to the API). */
  decision?: ChatDecision;
}

export interface ChatRequest {
  messages: ChatMessage[];
  /** Server-side conversation ID for lightweight mode (send only new messages). */
  conversationId?: string;
  modelId?: string;
  temperature?: number;
  maxTokens?: number;
  abortSignal?: AbortSignal;
  attachmentUrls?: string[];
  /** S3/MinIO object keys from POST /api/v1/storage/objects */
  attachmentObjectKeys?: string[];
  /**
   * Optional backend scenario override (Jackson enum name), e.g. IMPLEMENT_CHAIN.
   */
  scenarioHint?: string;
  /**
   * Answer to a decision card. When set, the server skips the scenario router and the
   * intent classifier and runs the typed facade command directly.
   */
  decision?: {
    action: string;
    artifactType?: string;
    artifactHash?: string;
    revision?: number;
    comment?: string;
  };
  context?: {
    type: "chain" | "service" | "operation";
    chainId?: string;
    serviceId?: string;
    operationId?: string;
    compactSchema?: {
      chainId: string;
      chainName: string;
      elements: Array<{
        id: string;
        name: string;
        type: string;
        serviceId?: string;
        operationId?: string;
        protocol?: string;
        parentElementId?: string;
      }>;
      connections: Array<{
        from: string;
        to: string;
      }>;
    };
  };
}

export interface ChatUsage {
  inputTokens?: number;
  outputTokens?: number;
  totalTokens?: number;
}

export interface ChatResponse {
  messages: ChatMessage[];
  usage?: ChatUsage;
  finishReason?: string;
  /** Server-side conversation ID returned by the backend. */
  conversationId?: string;
}

export interface ProviderCapabilities {
  supportsStreaming: boolean;
  supportsTools: boolean;
}

export type StreamingChunkType =
  | "meta"
  | "delta"
  | "step"
  | "decision"
  | "done"
  | "error";

/** SSE chunks from POST /api/v1/chat */
export interface StreamingChunk {
  type: StreamingChunkType;
  usage?: ChatUsage;
  finishReason?: string;
  errorMessage?: string;
  contentDelta?: string;
  conversationId?: string;
  step?: ActivityStepPayload;
  decision?: ChatDecision;
}

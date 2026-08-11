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
  | "hitl"
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
  hitl?: { checkpointId: string; question: string };
}

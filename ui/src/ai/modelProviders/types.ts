export type ChatRole = "system" | "user" | "assistant";

export interface ChatMessage {
  id?: string;
  role: ChatRole;
  content: string;
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

export interface HitlCheckpointPayload {
  checkpointId: string;
  question: string;
  options?: string[];
}

export type StreamingChunkType =
  | "done"
  | "error"
  | "progress"
  | "delta"
  | "hitl_checkpoint";

/** SSE chunks from POST /api/v1/chat */
export interface StreamingChunk {
  type: StreamingChunkType;
  usage?: ChatUsage;
  finishReason?: string;
  errorMessage?: string;
  progressMessage?: string;
  toolName?: string;
  toolArgs?: Record<string, unknown>;
  contentDelta?: string;
  conversationId?: string;
  hitlCheckpoint?: HitlCheckpointPayload;
}

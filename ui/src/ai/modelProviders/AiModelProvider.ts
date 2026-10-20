import {
  ChatRequest,
  ChatResponse,
  ProviderCapabilities,
  StreamingChunk,
} from "./types.ts";

export interface AiModelProvider {
  id: string;
  displayName: string;
  capabilities?: ProviderCapabilities;
  streamChat?(
    request: ChatRequest,
    onChunk: (chunk: StreamingChunk) => void,
  ): Promise<void>;
  /**
   * Chat with SSE progress events while tools run, then resolves with the final response.
   */
  chatWithProgress(
    request: ChatRequest,
    onChunk: (chunk: StreamingChunk) => void,
  ): Promise<ChatResponse>;
  chat?(request: ChatRequest): Promise<ChatResponse>;
  /** Upload a file for chat attachment; returns storage object key and download URL. Optional – HTTP provider only. */
  uploadFile?(
    file: File,
    sessionId?: string,
  ): Promise<{ url: string; objectKey: string }>;
}

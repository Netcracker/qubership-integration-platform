import { ChatRequest, ChatResponse, StreamingChunk } from "./types.ts";

export interface AiModelProvider {
  id: string;
  displayName: string;
  /**
   * Chat with SSE progress events while tools run, then resolves with the final response.
   */
  chatWithProgress(
    request: ChatRequest,
    onChunk: (chunk: StreamingChunk) => void,
  ): Promise<ChatResponse>;
  /** Upload a file for chat attachment; returns object URL. Optional – only HTTP provider supports it. */
  uploadFile?(file: File, sessionId?: string): Promise<{ url: string }>;
}

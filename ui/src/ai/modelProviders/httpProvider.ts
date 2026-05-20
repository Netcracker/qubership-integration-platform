import { AiModelProvider } from "./AiModelProvider.ts";
import {
  ChatRequest,
  ChatResponse,
  ChatMessage,
  StreamingChunk,
} from "./types.ts";
import axios from "axios";
import { getHeadersForContext } from "../../api/rest/requestHeadersInterceptor.ts";

type ChatRequestBody = Omit<ChatRequest, "abortSignal">;

function buildRequestBody(request: ChatRequest): ChatRequestBody {
  return {
    messages: request.messages,
    conversationId: request.conversationId,
    modelId: request.modelId,
    temperature: request.temperature,
    maxTokens: request.maxTokens,
    context: request.context,
    attachmentUrls: request.attachmentUrls,
  };
}

function getBearerHeader(
  serviceUrl: string,
  path: string,
): Record<string, string> {
  const base = serviceUrl.replace(/\/$/, "");
  const url = `${base}${path}`;
  const raw = getHeadersForContext({ url, baseURL: base });
  const auth = raw?.Authorization;
  if (typeof auth !== "string") return {};
  return { Authorization: auth };
}

export class HttpAiModelProvider implements AiModelProvider {
  id = "http";
  displayName = "HTTP AI Service Provider";

  constructor(private serviceUrl: string) {
    if (!serviceUrl) {
      throw new Error("AI service URL is required");
    }
  }

  /**
   * Streams progress events (SSE) as tools run, then returns the final response.
   */
  async chatWithProgress(
    request: ChatRequest,
    onChunk: (chunk: StreamingChunk) => void,
  ): Promise<ChatResponse> {
    const url = `${this.serviceUrl.replace(/\/$/, "")}/api/chat/with-progress`;

    const controller = new AbortController();
    const signal = request.abortSignal ?? controller.signal;

    const requestBody = buildRequestBody(request);

    const headers: Record<string, string> = {
      "Content-Type": "application/json",
      ...getBearerHeader(this.serviceUrl, "/api/chat/with-progress"),
    };
    const response = await fetch(url, {
      method: "POST",
      headers,
      body: JSON.stringify(requestBody),
      signal,
    });

    if (!response.ok || !response.body) {
      const text = await response.text().catch(() => "");
      throw new Error(text || `Request failed with status ${response.status}`);
    }

    const reader = response.body.getReader();
    const decoder = new TextDecoder("utf-8");
    let buffer = "";

    return new Promise<ChatResponse>((resolve, reject) => {
      const processBuffer = (): void => {
        const parts = buffer.split("\n\n");
        buffer = parts.pop() ?? "";

        for (const part of parts) {
          const line = part.trim();
          if (!line.startsWith("data:")) continue;

          const payload = line.slice("data:".length).trim();
          if (!payload) continue;

          try {
            const parsed = JSON.parse(payload) as {
              type: string;
              progressMessage?: string;
              toolName?: string;
              toolArgs?: Record<string, unknown>;
              messages?: ChatMessage[];
              usage?: ChatResponse["usage"];
              finishReason?: string;
              errorMessage?: string;
              conversationId?: string;
            };
            if (parsed.type === "progress" && parsed.progressMessage) {
              onChunk({
                type: "progress",
                progressMessage: parsed.progressMessage,
                toolName: parsed.toolName,
                toolArgs: parsed.toolArgs,
              });
            } else if (parsed.type === "done") {
              onChunk({
                type: "done",
                finishReason: parsed.finishReason,
                usage: parsed.usage,
              });
              resolve({
                messages: parsed.messages ?? [],
                usage: parsed.usage,
                finishReason: parsed.finishReason,
                conversationId: parsed.conversationId,
              });
              return;
            } else if (parsed.type === "error") {
              const msg = parsed.errorMessage ?? "Unknown error";
              onChunk({ type: "error", errorMessage: msg });
              reject(new Error(msg));
              return;
            }
          } catch {
            onChunk({ type: "error", errorMessage: "Failed to parse chunk" });
            reject(new Error("Failed to parse response chunk"));
            return;
          }
        }
      };

      void (async () => {
        try {
          let streamDone = false;
          while (!streamDone) {
            const { done, value } = await reader.read();
            if (done) {
              streamDone = true;
              break;
            }
            buffer += decoder.decode(value, { stream: true });
            processBuffer();
          }
          if (buffer.trim()) processBuffer();
        } catch (err: unknown) {
          const message = err instanceof Error ? err.message : "Request failed";
          onChunk({ type: "error", errorMessage: message });
          reject(err instanceof Error ? err : new Error(String(err)));
        } finally {
          if (!request.abortSignal) controller.abort();
        }
      })();
    });
  }

  async uploadFile(file: File, sessionId?: string): Promise<{ url: string }> {
    const url = `${this.serviceUrl.replace(/\/$/, "")}/api/upload`;
    const formData = new FormData();
    formData.append("file", file);
    if (sessionId) formData.append("sessionId", sessionId);
    const response = await axios.post<{ url: string }>(url, formData, {
      headers: {
        "Content-Type": "multipart/form-data",
        ...getBearerHeader(this.serviceUrl, "/api/upload"),
      },
      timeout: 60000,
      maxContentLength: 10 * 1024 * 1024,
      maxBodyLength: 10 * 1024 * 1024,
    });
    return response.data;
  }
}

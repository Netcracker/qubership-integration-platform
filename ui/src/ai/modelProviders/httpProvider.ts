import { AiModelProvider } from "./AiModelProvider.ts";
import {
  ChatRequest,
  ChatResponse,
  ChatMessage,
  ProviderCapabilities,
  StreamingChunk,
} from "./types.ts";
import { parseCipSseBlock } from "./sseParsing.ts";
import axios, { AxiosError } from "axios";
import { getHeadersForContext } from "../../api/rest/requestHeadersInterceptor.ts";

const capabilities: ProviderCapabilities = {
  supportsStreaming: true,
  supportsTools: true,
};

/** Body for POST /api/v1/chat (Quarkus ChatController). */
interface CipChatRequestBody {
  message: string;
  conversationId?: string;
  attachment?: string;
  attachmentObjectKeys?: string[];
  scenarioHint?: string | null;
  decision?: ChatRequest["decision"];
}

const CREATE_OWNED_HINTS = new Set([
  "IMPLEMENT_CHAIN",
  "GATHER_REQUIREMENTS",
  "CREATE_CHAIN_PLAN",
]);

function openChainId(request: ChatRequest): string | undefined {
  if (request.context?.type !== "chain") {
    return undefined;
  }
  return request.context.chainId ?? request.context.compactSchema?.chainId;
}

function resolveScenarioHint(request: ChatRequest): string | undefined {
  const hint = request.scenarioHint?.trim();
  if (!hint) {
    return undefined;
  }
  // A CREATE-owned hint names the page, not the request. Forwarding IMPLEMENT_CHAIN with a chain
  // open skips the classifier and starts a CREATE interview. Drop those hints; keep ASK_CHAIN,
  // COMPARE_AND_PATCH, and IMPORT_SPECIFICATION, which name a scenario.
  if (openChainId(request) && CREATE_OWNED_HINTS.has(hint)) {
    return undefined;
  }
  return hint;
}

function getApiErrorMessage(data: unknown): string | undefined {
  if (!data || typeof data !== "object") {
    return undefined;
  }
  const maybe = data as { error?: unknown };
  return typeof maybe.error === "string" ? maybe.error : undefined;
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
  capabilities = capabilities;

  constructor(private serviceUrl: string) {
    if (!serviceUrl) {
      throw new Error("AI service URL is required");
    }
  }

  async streamChat(
    request: ChatRequest,
    onChunk: (chunk: StreamingChunk) => void,
  ): Promise<void> {
    const base = this.serviceUrl.replace(/\/$/, "");
    const url = `${base}/api/v1/chat`;

    const controller = new AbortController();
    const signal = request.abortSignal ?? controller.signal;

    try {
      const hint = resolveScenarioHint(request);
      const requestBody: CipChatRequestBody = {
        message: this.extractLastUserMessage(request.messages),
        conversationId: request.conversationId,
        attachment: this.buildAttachment(request) || undefined,
        attachmentObjectKeys: this.mergeObjectKeys(
          request.attachmentObjectKeys,
        ),
        scenarioHint: hint ?? null,
        decision: request.decision,
      };

      const headers: Record<string, string> = {
        "Content-Type": "application/json",
        Accept: "text/event-stream",
        ...getBearerHeader(this.serviceUrl, "/api/v1/chat"),
      };

      const response = await fetch(url, {
        method: "POST",
        headers,
        body: JSON.stringify(requestBody),
        signal,
      });

      if (!response.ok || !response.body) {
        const text = await response.text().catch(() => "");
        throw new Error(
          text || `Streaming request failed with status ${response.status}`,
        );
      }

      const reader = response.body.getReader();
      const decoder = new TextDecoder("utf-8");
      let buffer = "";

      while (true) {
        const { done, value } = await reader.read();
        if (done) break;

        buffer += decoder.decode(value, { stream: true });

        const parts = buffer.split("\n\n");
        buffer = parts.pop() ?? "";

        for (const part of parts) {
          if (part.trim()) {
            this.parseSseBlock(part, onChunk);
          }
        }
      }

      if (buffer.trim()) {
        this.parseSseBlock(buffer, onChunk);
      }
    } catch (error) {
      if (error instanceof DOMException && error.name === "AbortError") {
        return;
      }
      if (error instanceof Error) {
        const lower = error.message.toLowerCase();
        if (lower.includes("aborted") || lower.includes("cancelled")) {
          return;
        }
      }
      const message =
        error instanceof Error
          ? error.message
          : "Failed to receive streaming response";
      onChunk({ type: "error", errorMessage: message });
    } finally {
      if (!request.abortSignal) {
        controller.abort();
      }
    }
  }

  async chatWithProgress(
    request: ChatRequest,
    onChunk: (chunk: StreamingChunk) => void,
  ): Promise<ChatResponse> {
    const messages: ChatMessage[] = [...request.messages];
    let conversationId: string | undefined;

    return new Promise<ChatResponse>((resolve, reject) => {
      this.streamChat(request, (chunk) => {
        onChunk(chunk);
        if (chunk.type === "done") {
          conversationId = chunk.conversationId;
          resolve({
            messages,
            conversationId,
            finishReason: chunk.finishReason,
            usage: chunk.usage,
          });
        } else if (chunk.type === "error") {
          reject(new Error(chunk.errorMessage ?? "Stream error"));
        } else if (chunk.type === "delta" && chunk.contentDelta) {
          const last = messages[messages.length - 1];
          if (last?.role === "assistant") {
            messages[messages.length - 1] = {
              ...last,
              content: last.content + chunk.contentDelta,
            };
          } else {
            messages.push({ role: "assistant", content: chunk.contentDelta });
          }
        }
      }).catch(reject);
    });
  }

  async chat(request: ChatRequest): Promise<ChatResponse> {
    const url = `${this.serviceUrl.replace(/\/$/, "")}/api/v1/chat`;
    try {
      const hint = resolveScenarioHint(request);
      const requestBody: CipChatRequestBody = {
        message: this.extractLastUserMessage(request.messages),
        conversationId: request.conversationId,
        attachment: this.buildAttachment(request) || undefined,
        attachmentObjectKeys: this.mergeObjectKeys(
          request.attachmentObjectKeys,
        ),
        scenarioHint: hint ?? null,
        decision: request.decision,
      };
      const response = await axios.post<ChatResponse>(url, requestBody, {
        headers: {
          "Content-Type": "application/json",
          ...getBearerHeader(this.serviceUrl, "/api/v1/chat"),
        },
        timeout: 600000,
        signal: request.abortSignal,
      });
      return response.data;
    } catch (error) {
      if (error instanceof AxiosError) {
        throw this.handleApiError(error);
      }
      throw error;
    }
  }

  async uploadFile(
    file: File,
    sessionId?: string,
  ): Promise<{ url: string; objectKey: string }> {
    const base = this.serviceUrl.replace(/\/$/, "");
    const endpoint = `${base}/api/v1/storage/objects`;
    const formData = new FormData();
    formData.append("file", file);
    if (sessionId) {
      formData.append("prefix", `sessions/${sessionId}`);
    }
    try {
      const response = await axios.post<{
        objectKey: string;
        size?: number;
        contentType?: string;
      }>(endpoint, formData, {
        headers: {
          "Content-Type": "multipart/form-data",
          ...getBearerHeader(this.serviceUrl, "/api/v1/storage/objects"),
        },
        timeout: 600000,
        maxContentLength: 10 * 1024 * 1024,
        maxBodyLength: 10 * 1024 * 1024,
      });
      if (!response.data?.objectKey) {
        throw new Error("Upload response missing objectKey");
      }
      const objectKey = response.data.objectKey;
      const url = `${base}/api/v1/storage/objects?key=${encodeURIComponent(objectKey)}`;
      return { url, objectKey };
    } catch (error) {
      if (error instanceof AxiosError) {
        throw this.handleApiError(error);
      }
      throw error;
    }
  }

  private parseSseBlock(
    block: string,
    onChunk: (chunk: StreamingChunk) => void,
  ): void {
    for (const chunk of parseCipSseBlock(block)) {
      onChunk(chunk);
    }
  }

  private extractLastUserMessage(messages: ChatMessage[]): string {
    for (let i = messages.length - 1; i >= 0; i--) {
      if (messages[i].role === "user") {
        return messages[i].content;
      }
    }
    return messages[messages.length - 1]?.content ?? "";
  }

  private mergeObjectKeys(keys: string[] | undefined): string[] | undefined {
    if (!keys?.length) {
      return undefined;
    }
    return [...new Set(keys)];
  }

  private buildAttachment(request: ChatRequest): string {
    const parts: string[] = [];

    // One line naming the open chain, and no more. The full compactSchema stays out for the reason
    // it was taken out: open-canvas JSON in effectiveUserText buries CREATE discovery in element
    // dumps. A name and an id are all the server needs to know which chain the reader is looking
    // at, and without them it cannot tell a change request from a new integration being described.
    const chainId = openChainId(request);
    if (chainId) {
      const chainName = request.context?.compactSchema?.chainName ?? "chain";
      parts.push(`## Current Chain: ${chainName} (ID: ${chainId})`);
    }

    if (request.attachmentUrls?.length) {
      parts.push(request.attachmentUrls.map((u) => `- ${u}`).join("\n"));
    }

    return parts.join("\n\n");
  }

  private handleApiError(error: AxiosError): Error {
    if (error.response) {
      const status = error.response.status;
      const message = getApiErrorMessage(error.response.data);

      if (status === 400) return new Error(message || "Invalid request");
      if (status === 401) {
        return new Error("Unauthorized. Check AI service configuration.");
      }
      if (status === 403) return new Error("Access forbidden");
      if (status === 429) {
        return new Error("Service is busy. Please try again in a moment.");
      }
      if (status === 503)
        return new Error(message || "AI service is not available.");
      if (status >= 500) {
        return new Error(
          message || "AI service error. Please try again later.",
        );
      }
      return new Error(message || `API error: ${status}`);
    }

    if (error.request) {
      return new Error(
        "Network error: Unable to reach AI service. Please check if the service is running.",
      );
    }

    return new Error(`Request error: ${error.message}`);
  }
}

/** @jest-environment jsdom */

import { beforeEach, describe, expect, it, jest } from "@jest/globals";
import "@testing-library/jest-dom";
import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import type { ReactNode } from "react";
import type { ChainContext } from "../../../src/components/ai/useChainContext.ts";
import { applyChainModificationProposal } from "../../../src/components/ai/applyChainModificationProposal.ts";
import { AiAssistant } from "../../../src/components/ai/AiAssistant.tsx";
import { getChatSessionStore } from "../../../src/ai/sessions/sessionStore.ts";
import { getDefaultAiProvider } from "../../../src/ai/config.ts";
import { fetchOpenDecision } from "../../../src/api/ai/decisionClient.ts";
import {
  fetchChainPlanStatus,
  approveChainPlanForBuild,
  dismissChainPlanOpenItems,
} from "../../../src/api/ai/chainPlanClient.ts";
import {
  resetConversation,
  truncateConversation,
  resolveServerMessageIndexes,
} from "../../../src/components/ai/conversationTurnApi.ts";
import { restoreServerMessageIndexes } from "../../../src/components/ai/conversationTurnIndex.ts";
import { publishCatalogChange } from "../../../src/misc/catalog-change-events.ts";
import type {
  ChatDecision,
  ChatMessage,
  ChatRequest,
  ChatResponse,
  StreamingChunk,
} from "../../../src/ai/modelProviders/types.ts";
import { useChainContext } from "../../../src/components/ai/useChainContext.ts";
import { downloadChainsExport } from "../../../src/misc/chain-export.ts";

Object.defineProperty(window, "matchMedia", {
  writable: true,
  value: jest.fn().mockImplementation(() => ({
    matches: false,
    addListener: jest.fn(),
    removeListener: jest.fn(),
    addEventListener: jest.fn(),
    removeEventListener: jest.fn(),
  })),
});
Object.defineProperty(navigator, "clipboard", {
  value: { writeText: jest.fn<() => Promise<void>>().mockResolvedValue() },
  configurable: true,
});

jest.mock("../../../src/ai/config.ts", () => ({
  getDefaultAiProvider: jest.fn(),
}));
jest.mock("antd/es/input/index", () => ({
  __esModule: true,
  default: jest.requireActual<typeof import("antd")>("antd").Input,
}));
jest.mock("../../../src/appConfig.ts", () => ({
  getConfig: () => ({ aiAssistantName: "Rocky" }),
}));
jest.mock("../../../src/ai/appConfig.ts", () => ({
  getAiServiceUrl: () => "http://ai.test",
}));
jest.mock("../../../src/icons/IconProvider.tsx", () => ({
  OverridableIcon: () => null,
}));
jest.mock("../../../src/api/api.ts", () => ({ api: {} }));
jest.mock("../../../src/components/ai/useChainContext.ts", () => ({
  useChainContext: jest.fn(),
}));
jest.mock("../../../src/api/ai/decisionClient.ts", () => ({
  fetchOpenDecision: jest.fn(),
}));
jest.mock("../../../src/api/ai/chainPlanClient.ts", () => ({
  fetchChainPlanStatus: jest.fn(),
  approveChainPlanForBuild: jest.fn(),
  dismissChainPlanOpenItems: jest.fn(),
}));
jest.mock("../../../src/components/ai/conversationTurnApi.ts", () => ({
  resetConversation: jest.fn(),
  truncateConversation: jest.fn(),
  resolveServerMessageIndexes: jest.fn(),
}));
jest.mock("../../../src/misc/catalog-change-events.ts", () => ({
  publishCatalogChange: jest.fn(),
}));
jest.mock("../../../src/misc/chain-export.ts", () => ({
  downloadChainsExport: jest.fn(),
}));
const mockNotify = { warning: jest.fn(), requestFailed: jest.fn() };
jest.mock("../../../src/hooks/useNotificationService.tsx", () => ({
  useNotificationService: () => mockNotify,
}));
const mockShowModal = jest.fn<(options: { component: ReactNode }) => void>();
jest.mock("../../../src/Modals.tsx", () => ({
  useModalsContext: () => ({ showModal: mockShowModal }),
}));
jest.mock("../../../src/components/ai/AiMarkdownRenderer.tsx", () => ({
  MarkdownRenderer: ({ children }: { children: ReactNode }) => children,
}));
jest.mock(
  "../../../src/components/ai/applyChainModificationProposal.ts",
  () => ({ applyChainModificationProposal: jest.fn() }),
);
jest.mock("../../../src/components/modal/ExportChains.tsx", () => ({
  ExportChains: ({
    onSubmit,
  }: {
    onSubmit: (options: {
      exportSubchains: boolean;
      exportServices: boolean;
      exportVariables: boolean;
    }) => void;
  }) => (
    <button
      onClick={() =>
        onSubmit({
          exportSubchains: true,
          exportServices: true,
          exportVariables: false,
        })
      }
    >
      Download archive
    </button>
  ),
}));
jest.mock("../../../src/misc/confirm-utils.ts", () => ({
  confirmAndRun: ({ onOk }: { onOk: () => void }) => onOk(),
}));

const store = getChatSessionStore();
const chat = jest.fn<(request: ChatRequest) => Promise<ChatResponse>>();
const progress =
  jest.fn<
    (
      request: ChatRequest,
      onChunk: (chunk: StreamingChunk) => void,
    ) => Promise<ChatResponse>
  >();
const stream =
  jest.fn<
    (
      request: ChatRequest,
      onChunk: (chunk: StreamingChunk) => void,
    ) => Promise<void>
  >();
const upload =
  jest.fn<
    (
      file: File,
      sessionId: string,
    ) => Promise<{ url: string; objectKey: string }>
  >();
const provider = {
  id: "test",
  displayName: "Test",
  chat,
  chatWithProgress: progress,
  streamChat: stream,
  uploadFile: upload,
  capabilities: { supportsStreaming: true, supportsTools: false },
};

function chainContext(): ChainContext {
  return {
    chain: {
      id: "chain-1",
      name: "Greeting",
      description: "",
      navigationPath: [],
      elements: [],
      dependencies: [],
      deployments: [],
      labels: [],
      defaultSwimlaneId: "main",
      reuseSwimlaneId: "reuse",
      unsavedChanges: false,
      businessDescription: "",
      assumptions: "",
      outOfScope: "",
      containsDeprecatedContainers: false,
      containsDeprecatedElements: false,
      containsUnsupportedElements: false,
    },
    compactSchema: {
      chainId: "chain-1",
      chainName: "Greeting",
      elements: [],
      connections: [],
    },
    refresh: jest.fn<() => Promise<void>>().mockResolvedValue(undefined),
  };
}

function seed(messages: ChatMessage[], conversationId = "conv-1") {
  const session = store.createSession();
  store.updateSessionMessages(session.id, messages);
  store.updateConversationId(session.id, conversationId);
  return session.id;
}

async function open() {
  const result = render(<AiAssistant />);
  fireEvent.click(screen.getByRole("button", { name: "Rocky" }));
  await screen.findByPlaceholderText("Ask Rocky about this chain...");
  return result;
}

async function send(text = "Create a greeting chain") {
  fireEvent.change(
    screen.getByPlaceholderText("Ask Rocky about this chain..."),
    { target: { value: text } },
  );
  fireEvent.click(screen.getByRole("button", { name: "Send" }));
  await waitFor(() =>
    expect(screen.getByRole("button", { name: "Send" })).toBeEnabled(),
  );
}

const decision = (overrides: Partial<ChatDecision> = {}): ChatDecision => ({
  id: "gate-1",
  kind: "approve",
  question: "Approve the plan?",
  artifactType: "CHAIN_PLAN",
  artifactHash: "hash-1",
  revision: 1,
  actions: ["approve", "request-changes"],
  ...overrides,
});

beforeEach(() => {
  for (const session of store.getAllSessions()) store.deleteSession(session.id);
  jest.mocked(getDefaultAiProvider).mockReturnValue(provider);
  jest.mocked(useChainContext).mockReturnValue(null);
  jest.mocked(fetchOpenDecision).mockResolvedValue(null);
  jest.mocked(fetchChainPlanStatus).mockResolvedValue(null);
  jest.mocked(resetConversation).mockResolvedValue(undefined);
  jest.mocked(truncateConversation).mockResolvedValue(undefined);
  jest
    .mocked(resolveServerMessageIndexes)
    .mockImplementation(async (_url, _conversation, messages) => messages);
  jest.mocked(downloadChainsExport).mockResolvedValue(undefined);
  jest.mocked(applyChainModificationProposal).mockResolvedValue(undefined);
  stream.mockImplementation(async (_request, emit) => {
    emit({ type: "delta", contentDelta: "Created the greeting chain" });
    emit({ type: "done", conversationId: "conv-1" });
  });
  progress.mockResolvedValue({
    messages: [{ role: "assistant", content: "Completed response" }],
    conversationId: "conv-1",
  });
  upload.mockResolvedValue({
    url: "http://files.test/design",
    objectKey: "design.md",
  });
  provider.capabilities.supportsStreaming = true;
  jest.spyOn(console, "warn").mockImplementation(() => undefined);
  jest.spyOn(console, "error").mockImplementation(() => undefined);
});

describe("AiAssistant", () => {
  it("streams a response into the mounted conversation and names the session", async () => {
    await open();
    await send();
    expect(
      await screen.findByText("Created the greeting chain"),
    ).toBeInTheDocument();
    const session = store.getAllSessions()[0];
    expect(session.title).toBe("Create a greeting chain");
    expect(session.conversationId).toBe("conv-1");
    expect(stream.mock.calls[0][0].messages).toEqual([
      expect.objectContaining({
        role: "user",
        content: "Create a greeting chain",
      }),
    ]);
    expect(publishCatalogChange).toHaveBeenCalledWith({
      chainIds: null,
      chainsListChanged: true,
    });
  });

  it("keeps server catalog events and the conversation ID from stream metadata", async () => {
    stream.mockImplementation(async (_request, emit) => {
      emit({
        type: "meta",
        conversationId: "server-conversation",
        supportsCatalogChangeEvents: true,
      });
      emit({
        type: "catalog-changed",
        catalogChange: { chainIds: ["chain-1"], chainsListChanged: true },
      });
      emit({ type: "delta", contentDelta: "Saved" });
      emit({ type: "done" });
    });
    await open();
    await send("Save this chain");
    expect(await screen.findByText("Saved")).toBeInTheDocument();
    expect(store.getAllSessions()[0].conversationId).toBe(
      "server-conversation",
    );
    expect(publishCatalogChange).toHaveBeenCalledTimes(1);
    expect(publishCatalogChange).toHaveBeenCalledWith({
      chainIds: ["chain-1"],
      chainsListChanged: true,
    });
  });

  it("uses progress responses when streaming is unavailable", async () => {
    provider.capabilities.supportsStreaming = false;
    progress.mockImplementation(async (_request, emit) => {
      emit({ type: "meta", supportsCatalogChangeEvents: true });
      emit({
        type: "catalog-changed",
        catalogChange: { chainIds: ["c1"], chainsListChanged: true },
      });
      emit({ type: "done" });
      return {
        messages: [{ role: "assistant", content: "Progress completed" }],
        conversationId: "conv-1",
        finishReason: "stop",
        usage: { promptTokens: 10, completionTokens: 5, totalTokens: 15 },
      };
    });
    await open();
    await send();
    expect(await screen.findByText("Progress completed")).toBeInTheDocument();
    expect(stream).not.toHaveBeenCalled();
    expect(progress).toHaveBeenCalledTimes(1);
    expect(mockNotify.warning).not.toHaveBeenCalled();
  });

  it("keeps one failure and refreshes the catalog after a transport interruption", async () => {
    stream.mockImplementation(async (_request, emit) => {
      emit({ type: "delta", contentDelta: "Partial answer" });
      emit({
        type: "error",
        errorMessage: "Connection lost",
        transportError: true,
      });
    });
    await open();
    await send();
    expect(await screen.findByText("Connection lost")).toBeInTheDocument();
    expect(
      store.getAllSessions()[0].messages.filter((m) => m.variant === "error"),
    ).toHaveLength(1);
    expect(mockNotify.warning).toHaveBeenCalledTimes(1);
  });

  it("shows provider initialization errors without calling the network", async () => {
    jest.mocked(getDefaultAiProvider).mockImplementation(() => {
      throw new Error("Missing provider configuration");
    });
    await open();
    await send();
    expect(
      screen.getAllByText("Missing provider configuration").length,
    ).toBeGreaterThan(0);
    expect(stream).not.toHaveBeenCalled();
  });

  it("records a rejected request and reconciles the durable decision afterward", async () => {
    stream.mockRejectedValue(new Error("Provider rejected request"));
    await open();
    await send();
    expect(
      await screen.findByText("Provider rejected request"),
    ).toBeInTheDocument();
    expect(fetchOpenDecision).toHaveBeenCalled();
  });

  it("restores and answers a server decision with its exact revision", async () => {
    const gate = decision();
    const id = seed([{ role: "user", content: "Build a chain" }]);
    jest.mocked(fetchOpenDecision).mockResolvedValue(gate);
    await open();
    fireEvent.click(await screen.findByRole("button", { name: "Approve" }));
    await waitFor(() => expect(stream).toHaveBeenCalledTimes(1));
    expect(stream.mock.calls[0][0].decision).toEqual({
      action: "approve",
      artifactType: gate.artifactType,
      artifactHash: gate.artifactHash,
      revision: gate.revision,
      comment: undefined,
    });
    expect(stream.mock.calls[0][0].messages).toEqual([]);
    expect(
      store
        .getSession(id)
        ?.messages.some((m) => m.decision?.answeredAction === "approve"),
    ).toBe(true);
  });

  it("sends clarification text as a user turn with the retained attachments", async () => {
    const gate = decision({ kind: "clarify", actions: [] });
    const id = seed([{ role: "user", content: "Build a chain" }]);
    store.updateSessionLastAttachmentUrls(id, ["http://files.test/original"]);
    store.updateSessionLastAttachmentObjectKeys(id, ["original.md"]);
    jest.mocked(fetchOpenDecision).mockResolvedValue(gate);
    await open();
    await screen.findByText("Approve the plan?");
    await send("Use GET and ROLE_CIP_ADMIN");
    expect(stream.mock.calls[0][0]).toMatchObject({
      messages: [
        expect.objectContaining({
          role: "user",
          content: "Use GET and ROLE_CIP_ADMIN",
        }),
      ],
      attachmentUrls: ["http://files.test/original"],
      attachmentObjectKeys: ["original.md"],
    });
    expect(stream.mock.calls[0][0].decision).toBeUndefined();
  });

  it("uploads files, preserves their keys, and supplies attachment-only text", async () => {
    const { container } = await open();
    const input = container.ownerDocument.querySelector('input[type="file"]')!;
    fireEvent.change(input, {
      target: { files: [new File(["design"], "design.md")] },
    });
    expect(screen.getByText("design.md")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Send" }));
    await waitFor(() => expect(stream).toHaveBeenCalledTimes(1));
    expect(stream.mock.calls[0][0]).toMatchObject({
      attachmentUrls: ["http://files.test/design"],
      attachmentObjectKeys: ["design.md"],
      messages: [expect.objectContaining({ content: "See attached files." })],
    });
  });

  it("edits a visible user message after truncating the server transcript", async () => {
    const id = seed([
      { role: "user", content: "Original request", serverMessageIndex: 0 },
      { role: "assistant", content: "Original reply" },
    ]);
    await open();
    fireEvent.click(
      screen.getByRole("button", { name: "Edit message and send again" }),
    );
    await waitFor(() =>
      expect(
        screen.getByPlaceholderText("Ask Rocky about this chain..."),
      ).toHaveValue("Original request"),
    );
    expect(truncateConversation).toHaveBeenCalledWith(
      "http://ai.test",
      "conv-1",
      -1,
    );
    expect(store.getSession(id)?.messages).toEqual([]);
    await send("Updated request");
    expect(stream.mock.calls[0][0].messages).toEqual([
      expect.objectContaining({ content: "Updated request" }),
    ]);
  });

  it("regenerates from the preceding user turn and retains attachment keys", async () => {
    const id = seed([
      { role: "user", content: "Original request", serverMessageIndex: 0 },
      { role: "assistant", content: "Original reply" },
    ]);
    store.updateSessionLastAttachmentObjectKeys(id, ["design.md"]);
    await open();
    fireEvent.click(
      screen.getByRole("button", { name: "Regenerate this answer" }),
    );
    await waitFor(() => expect(stream).toHaveBeenCalledTimes(1));
    expect(truncateConversation).toHaveBeenCalledWith(
      "http://ai.test",
      "conv-1",
      -1,
    );
    expect(stream.mock.calls[0][0]).toMatchObject({
      messages: [expect.objectContaining({ content: "Original request" })],
      attachmentObjectKeys: ["design.md"],
    });
  });

  it("clears the server conversation and all retained attachments", async () => {
    const id = seed([{ role: "user", content: "Original request" }]);
    store.updateSessionLastAttachmentUrls(id, ["http://files.test/original"]);
    store.updateSessionLastAttachmentObjectKeys(id, ["original.md"]);
    await open();
    fireEvent.click(screen.getByRole("button", { name: "Clear" }));
    await waitFor(() => expect(store.getSession(id)?.messages).toEqual([]));
    expect(resetConversation).toHaveBeenCalledWith("http://ai.test", "conv-1");
    expect(store.getSession(id)?.lastAttachmentObjectKeys).toBeUndefined();
    expect(store.getSession(id)?.lastAttachmentUrls).toBeUndefined();
  });

  it.each([true, false])(
    "records server positions in streaming mode %s",
    async (streaming) => {
      provider.capabilities.supportsStreaming = streaming;
      const id = seed([
        { role: "user", content: "original", serverMessageIndex: 0 },
        { role: "assistant", content: "done" },
      ]);
      stream.mockImplementation(async (_request, emit) => {
        emit({ type: "meta", conversationId: "conv-1", userMessageIndex: 4 });
        emit({ type: "delta", contentDelta: "new answer" });
        emit({ type: "done", conversationId: "conv-1" });
      });
      progress.mockImplementation(async (request, emit) => {
        emit({ type: "meta", conversationId: "conv-1", userMessageIndex: 4 });
        emit({ type: "done", conversationId: "conv-1" });
        return {
          messages: [
            ...request.messages,
            { role: "assistant", content: "new answer" },
          ],
          conversationId: "conv-1",
        };
      });
      await open();
      await send("next");
      const users = store
        .getSession(id)!
        .messages.filter((m) => m.role === "user");
      expect(users.map((m) => m.serverMessageIndex)).toEqual([0, 4]);
    },
  );

  it("does not assign an approval input's position to the preceding user bubble", async () => {
    const id = seed([
      { role: "user", content: "original", serverMessageIndex: 0 },
    ]);
    jest.mocked(fetchOpenDecision).mockResolvedValue(decision());
    stream.mockImplementation(async (_request, emit) => {
      emit({ type: "meta", conversationId: "conv-1", userMessageIndex: 2 });
      emit({ type: "delta", contentDelta: "approved" });
      emit({ type: "done", conversationId: "conv-1" });
    });
    await open();
    fireEvent.click(await screen.findByRole("button", { name: "Approve" }));
    await waitFor(() => expect(stream).toHaveBeenCalledTimes(1));
    expect(
      store.getSession(id)!.messages.find((m) => m.role === "user")!
        .serverMessageIndex,
    ).toBe(0);
  });

  it.each(["edit", "regenerate"])(
    "restores legacy positions before %s of a repeated message",
    async (action) => {
      const id = seed([
        { role: "user", content: "continue" },
        { role: "assistant", content: "same answer" },
        { role: "user", content: "continue" },
        { role: "assistant", content: "same answer" },
      ]);
      jest
        .mocked(resolveServerMessageIndexes)
        .mockImplementation(async (_url, _conversation, messages) =>
          restoreServerMessageIndexes(messages, [
            { messageIndex: 0, content: "continue" },
            { messageIndex: 6, content: "continue" },
          ]),
        );
      await open();
      if (action === "edit") {
        fireEvent.click(
          screen.getAllByRole("button", {
            name: "Edit message and send again",
          })[1],
        );
      } else {
        fireEvent.click(
          screen.getByRole("button", { name: "Regenerate this answer" }),
        );
      }
      await waitFor(() =>
        expect(truncateConversation).toHaveBeenCalledWith(
          "http://ai.test",
          "conv-1",
          5,
        ),
      );
      await waitFor(() =>
        expect(store.getSession(id)!.messages[0].serverMessageIndex).toBe(0),
      );
      expect(store.getSession(id)!.messages[1].content).toBe("same answer");
      if (action === "edit") {
        expect(store.getSession(id)!.messages).toHaveLength(2);
        expect(
          screen.getByPlaceholderText("Ask Rocky about this chain..."),
        ).toHaveValue("continue");
      } else {
        await waitFor(() => expect(stream).toHaveBeenCalledTimes(1));
        expect(
          stream.mock.calls[0][0].messages.filter((m) => m.role === "user"),
        ).toHaveLength(2);
      }
    },
  );

  it.each(["edit", "regenerate"])(
    "preserves a mismatched legacy history during %s",
    async (action) => {
      const id = seed([
        { role: "user", content: "old" },
        { role: "assistant", content: "keep this" },
      ]);
      jest
        .mocked(resolveServerMessageIndexes)
        .mockRejectedValue(
          new Error(
            "The saved conversation does not match the server history.",
          ),
        );
      await open();
      const before = store.getSession(id)!.messages;
      fireEvent.click(
        screen.getByRole("button", {
          name:
            action === "edit"
              ? "Edit message and send again"
              : "Regenerate this answer",
        }),
      );
      await waitFor(() =>
        expect(resolveServerMessageIndexes).toHaveBeenCalledTimes(1),
      );
      expect(truncateConversation).not.toHaveBeenCalled();
      expect(stream).not.toHaveBeenCalled();
      expect(store.getSession(id)!.messages).toEqual(before);
    },
  );

  it("creates and deletes chats through the panel controls", async () => {
    await open();
    const first = store.getAllSessions()[0].id;
    fireEvent.click(screen.getByRole("button", { name: "New chat" }));
    expect(store.getAllSessions()).toHaveLength(2);
    fireEvent.click(screen.getByRole("button", { name: "Delete chat" }));
    expect(store.getAllSessions().map((s) => s.id)).toEqual([first]);
  });
  it("approves an active plan before sending the build command", async () => {
    seed([{ role: "user", content: "Design a chain" }]);
    const status = {
      hasActivePlan: true,
      approved: false,
      planId: "plan-1",
      chainName: "Greeting",
      updatedAt: null,
      openItemCount: 0,
    };
    jest.mocked(fetchChainPlanStatus).mockResolvedValue(status);
    jest
      .mocked(approveChainPlanForBuild)
      .mockResolvedValue({ ...status, approved: true });
    await open();
    fireEvent.click(await screen.findByRole("button", { name: "Build chain" }));
    await waitFor(() => expect(stream).toHaveBeenCalledTimes(1));
    expect(approveChainPlanForBuild).toHaveBeenCalledWith("conv-1");
    expect(stream.mock.calls[0][0]).toMatchObject({
      scenarioHint: "IMPLEMENT_CHAIN",
      messages: [
        expect.objectContaining({
          content:
            "Implement the approved chain implementation plan in the catalog.",
        }),
      ],
    });
    expect(dismissChainPlanOpenItems).not.toHaveBeenCalled();
  });

  it("dismisses open plan items only after confirmation", async () => {
    seed([{ role: "user", content: "Design a chain" }]);
    const status = {
      hasActivePlan: true,
      approved: false,
      planId: "plan-1",
      chainName: "Greeting",
      updatedAt: null,
      openItemCount: 2,
    };
    jest.mocked(fetchChainPlanStatus).mockResolvedValue(status);
    jest
      .mocked(dismissChainPlanOpenItems)
      .mockResolvedValue({ ...status, openItemCount: 0 });
    jest
      .mocked(approveChainPlanForBuild)
      .mockResolvedValue({ ...status, approved: true });
    await open();
    fireEvent.click(await screen.findByRole("button", { name: "Build chain" }));
    expect(stream).not.toHaveBeenCalled();
    fireEvent.click(
      await screen.findByRole("button", { name: "Dismiss and build" }),
    );
    await waitFor(() => expect(stream).toHaveBeenCalledTimes(1));
    expect(dismissChainPlanOpenItems).toHaveBeenCalledWith("conv-1");
    expect(
      jest.mocked(dismissChainPlanOpenItems).mock.invocationCallOrder[0],
    ).toBeLessThan(
      jest.mocked(approveChainPlanForBuild).mock.invocationCallOrder[0],
    );
  });

  it("aborts an in-flight request and keeps the conversation for retry", async () => {
    let started: ChatRequest | undefined;
    stream.mockImplementation(
      (request) =>
        new Promise<void>((resolve) => {
          started = request;
          request.abortSignal?.addEventListener("abort", () => resolve());
        }),
    );
    await open();
    fireEvent.change(
      screen.getByPlaceholderText("Ask Rocky about this chain..."),
      { target: { value: "Build the chain" } },
    );
    fireEvent.click(screen.getByRole("button", { name: "Send" }));
    await screen.findByRole("button", { name: "Stop" });
    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: "Stop" }));
    });
    expect(started?.abortSignal?.aborted).toBe(true);
    expect(store.getAllSessions()[0].conversationId).toBe(
      started?.conversationId,
    );
    expect(mockNotify.warning).toHaveBeenCalledTimes(1);
    expect(
      screen.queryByRole("button", { name: "Stop" }),
    ).not.toBeInTheDocument();
    expect(
      store.getAllSessions()[0].messages.filter((m) => m.variant === "error"),
    ).toEqual([]);
  });

  it("keeps the original transcript when server truncation fails", async () => {
    const id = seed([
      { role: "user", content: "Original request", serverMessageIndex: 0 },
      { role: "assistant", content: "Original reply" },
    ]);
    jest
      .mocked(truncateConversation)
      .mockRejectedValue(new Error("Server rejected truncation"));
    await open();
    fireEvent.click(
      screen.getByRole("button", { name: "Edit message and send again" }),
    );
    await waitFor(() => expect(truncateConversation).toHaveBeenCalledTimes(1));
    expect(store.getSession(id)?.messages.map((m) => m.content)).toEqual([
      "Original request",
      "Original reply",
    ]);
    expect(
      screen.getByPlaceholderText("Ask Rocky about this chain..."),
    ).toHaveValue("");
    expect(stream).not.toHaveBeenCalled();
  });

  it("does not drop an existing decision when reconciliation fails", async () => {
    const gate = decision();
    seed([{ role: "assistant", content: "", decision: gate }]);
    jest
      .mocked(fetchOpenDecision)
      .mockRejectedValue(new Error("Decision API unavailable"));
    await open();
    expect(
      await screen.findByRole("button", { name: "Approve" }),
    ).toBeEnabled();
    expect(store.getAllSessions()[0].messages[0].decision).toEqual(gate);
  });
  it("uses freshly loaded chain context and avoids a create hint for the open chain", async () => {
    const context = chainContext();
    const fresh = {
      ...context,
      compactSchema: { ...context.compactSchema, chainName: "Latest greeting" },
    };
    context.getFreshContext = jest
      .fn<() => Promise<ChainContext>>()
      .mockResolvedValue(fresh);
    jest.mocked(useChainContext).mockReturnValue(context);
    seed([{ role: "user", content: "Design this chain" }]);
    jest.mocked(fetchChainPlanStatus).mockResolvedValue({
      hasActivePlan: true,
      approved: true,
      planId: "p1",
      chainName: "Greeting",
      updatedAt: null,
      openItemCount: 0,
    });
    await open();
    fireEvent.click(await screen.findByRole("button", { name: "Build chain" }));
    await waitFor(() => expect(stream).toHaveBeenCalledTimes(1));
    expect(stream.mock.calls[0][0].scenarioHint).toBeUndefined();
    expect(stream.mock.calls[0][0].context).toEqual({
      type: "chain",
      chainId: "chain-1",
      compactSchema: fresh.compactSchema,
    });
    expect(context.getFreshContext).toHaveBeenCalledTimes(1);
  });

  it("keeps streamed narrative and activity before a decision card", async () => {
    const gate = decision();
    jest.mocked(fetchOpenDecision).mockResolvedValue(gate);
    stream.mockImplementation(async (_request, emit) => {
      emit({
        type: "step",
        step: {
          id: "planning",
          kind: "skill",
          status: "running",
          label: "Planning",
        },
      });
      emit({ type: "delta", contentDelta: "The design is ready." });
      emit({ type: "decision", decision: gate });
      emit({
        type: "step",
        step: {
          id: "planning",
          kind: "skill",
          status: "completed",
          label: "Planning",
        },
      });
      emit({ type: "done", finishReason: "stop" });
    });
    await open();
    await send();
    expect(await screen.findByText("The design is ready.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Approve" })).toBeEnabled();
    expect(
      store
        .getAllSessions()[0]
        .messages.some((m) =>
          m.activity?.steps.some((step) => step.id === "planning"),
        ),
    ).toBe(true);
  });

  it("exports selected chains only after the export dialog submits", async () => {
    const gate = decision({
      artifactType: "CHAIN_EXPORT",
      actions: ["export-chains"],
      chainIds: ["chain-1", "chain-2"],
    });
    seed([
      { role: "assistant", content: "Select export options", decision: gate },
    ]);
    jest.mocked(fetchOpenDecision).mockResolvedValue(gate);
    await open();
    fireEvent.click(await screen.findByRole("button", { name: /export/i }));
    expect(downloadChainsExport).not.toHaveBeenCalled();
    render(<>{mockShowModal.mock.calls[0][0].component}</>);
    fireEvent.click(screen.getByRole("button", { name: "Download archive" }));
    await waitFor(() => expect(downloadChainsExport).toHaveBeenCalledTimes(1));
    expect(jest.mocked(downloadChainsExport).mock.calls[0].slice(1)).toEqual([
      ["chain-1", "chain-2"],
      { exportSubchains: true, exportServices: true, exportVariables: false },
    ]);
    expect(
      store
        .getAllSessions()[0]
        .messages.some((m) => m.decision?.answeredAction === "export-chains"),
    ).toBe(true);
    expect(stream).not.toHaveBeenCalled();
  });

  it("starts the same assignment in a new conversation from a recovery card", async () => {
    const gate = decision({
      kind: "clarify",
      actions: ["retry", "stop-with-report"],
      answeredAction: "stop-with-report",
      reason: "recovery-repeated",
      recovery: {
        category: "unclassified-failure",
        title: "Repair stopped",
        summary: "No patch",
        preservedWork: "Original assignment",
        technicalDetails: "GRAPH_PATCH_ARTIFACT",
      },
    });
    const id = seed([
      { role: "user", content: "Original assignment" },
      { role: "assistant", content: "Repair stopped", decision: gate },
    ]);
    await open();
    fireEvent.click(await screen.findByRole("button", { name: /same task/i }));
    await waitFor(() => expect(stream).toHaveBeenCalledTimes(1));
    expect(store.getAllSessions()).toHaveLength(2);
    expect(stream.mock.calls[0][0].messages).toEqual([
      expect.objectContaining({ role: "user", content: "Original assignment" }),
    ]);
    expect(stream.mock.calls[0][0].conversationId).not.toBe(
      store.getSession(id)?.conversationId,
    );
  });

  it("applies a structured modification only after confirmation", async () => {
    const context = chainContext();
    jest.mocked(useChainContext).mockReturnValue(context);
    const proposal = {
      type: "chain-modification-proposal",
      chainId: "chain-1",
      changes: [{ action: "updateChain", patch: { name: "Renamed greeting" } }],
    };
    stream.mockImplementation(async (_request, emit) => {
      emit({ type: "delta", contentDelta: JSON.stringify(proposal) });
      emit({ type: "done" });
    });
    await open();
    await send("Rename the chain");
    expect(applyChainModificationProposal).not.toHaveBeenCalled();
    fireEvent.click(
      await screen.findByRole("button", { name: "Apply changes" }),
    );
    await waitFor(() =>
      expect(applyChainModificationProposal).toHaveBeenCalledTimes(1),
    );
    expect(
      jest.mocked(applyChainModificationProposal).mock.calls[0][0],
    ).toEqual(proposal);
    expect(jest.mocked(applyChainModificationProposal).mock.calls[0][2]).toBe(
      context,
    );
  });

  it("still sends user text when an attachment upload fails", async () => {
    upload.mockRejectedValue(new Error("Upload failed"));
    const { container } = await open();
    fireEvent.change(
      container.ownerDocument.querySelector('input[type="file"]')!,
      { target: { files: [new File(["design"], "design.md")] } },
    );
    await send("Use the written requirements");
    expect(stream.mock.calls[0][0].attachmentObjectKeys).toBeUndefined();
    expect(stream.mock.calls[0][0].messages).toEqual([
      expect.objectContaining({ content: "Use the written requirements" }),
    ]);
    expect(console.warn).toHaveBeenCalledWith(
      "[AiAssistant] Upload failed, sending without attachments",
      expect.any(Error),
    );
  });

  it("keeps an empty progress response without inventing an assistant message", async () => {
    provider.capabilities.supportsStreaming = false;
    progress.mockResolvedValue({ messages: [], conversationId: "conv-1" });
    await open();
    await send();
    expect(
      store.getAllSessions()[0].messages.filter((m) => m.role === "assistant"),
    ).toEqual([]);
    expect(mockNotify.warning).toHaveBeenCalledTimes(1);
  });

  it("sends Enter once and keeps Shift+Enter as a line break", async () => {
    await open();
    const composer = screen.getByPlaceholderText(
      "Ask Rocky about this chain...",
    );
    fireEvent.input(composer, { target: { value: "First line" } });
    fireEvent.keyDown(composer, { key: "Enter", shiftKey: true });
    expect(stream).not.toHaveBeenCalled();
    fireEvent.keyDown(composer, { key: "Enter" });
    await waitFor(() => expect(stream).toHaveBeenCalledTimes(1));
    expect(stream.mock.calls[0][0].messages[0].content).toBe("First line");
  });
  it("refreshes once when a progress stream ends with a transport failure", async () => {
    provider.capabilities.supportsStreaming = false;
    progress.mockImplementation(async (_request, emit) => {
      emit({
        type: "step",
        step: {
          id: "capture",
          kind: "tool",
          status: "error",
          label: "Capture",
        },
      });
      emit({
        type: "error",
        errorMessage: "Disconnected",
        transportError: true,
      });
      throw new Error("Disconnected");
    });
    await open();
    await send();
    expect(await screen.findByText("Disconnected")).toBeInTheDocument();
    expect(mockNotify.warning).toHaveBeenCalledTimes(1);
  });

  it("does not warn about uncertain catalog writes after a definitive server rejection", async () => {
    provider.capabilities.supportsStreaming = false;
    progress.mockImplementation(async (_request, emit) => {
      emit({ type: "error", errorMessage: "Rejected" });
      throw new Error("Rejected");
    });
    await open();
    await send();
    expect(await screen.findByText("Rejected")).toBeInTheDocument();
    expect(mockNotify.warning).not.toHaveBeenCalled();
  });

  it("ignores deltas after a failed stream and suppresses an abort error bubble", async () => {
    stream.mockImplementation(async (_request, emit) => {
      emit({ type: "error", errorMessage: "The operation was aborted" });
      emit({ type: "delta", contentDelta: "Stale answer" });
    });
    await open();
    await send();
    expect(screen.queryByText("Stale answer")).not.toBeInTheDocument();
    expect(
      store.getAllSessions()[0].messages.filter((m) => m.variant === "error"),
    ).toEqual([]);
  });

  it("caps a long session title at the first line", async () => {
    await open();
    await send("a".repeat(120) + "\nSecond line");
    expect(store.getAllSessions()[0].title).toBe("a".repeat(100) + "...");
  });

  it("copies assistant text and removes a picked file before sending", async () => {
    seed([{ role: "assistant", content: "Text to copy" }]);
    const { container } = await open();
    fireEvent.click(screen.getByRole("button", { name: "Copy" }));
    expect(navigator.clipboard.writeText).toHaveBeenCalledWith("Text to copy");
    fireEvent.change(
      container.ownerDocument.querySelector('input[type="file"]')!,
      { target: { files: [new File(["unused"], "unused.md")] } },
    );
    fireEvent.click(
      within(
        screen.getByText("unused.md").closest<HTMLElement>(".ant-tag")!,
      ).getByRole("button"),
    );
    await send("Use only this text");
    expect(upload).not.toHaveBeenCalled();
    expect(stream.mock.calls[0][0].attachmentUrls).toBeUndefined();
  });

  it("keeps the transcript when regeneration is rejected by the server", async () => {
    const id = seed([
      { role: "user", content: "Original request", serverMessageIndex: 0 },
      { role: "assistant", content: "Original reply" },
    ]);
    jest
      .mocked(truncateConversation)
      .mockRejectedValue(new Error("Cannot truncate"));
    await open();
    fireEvent.click(
      screen.getByRole("button", { name: "Regenerate this answer" }),
    );
    await waitFor(() => expect(truncateConversation).toHaveBeenCalledTimes(1));
    expect(store.getSession(id)?.messages.map((m) => m.content)).toEqual([
      "Original request",
      "Original reply",
    ]);
    expect(stream).not.toHaveBeenCalled();
  });

  it("clears local messages even if the remote reset fails", async () => {
    const id = seed([{ role: "user", content: "Original request" }]);
    jest
      .mocked(resetConversation)
      .mockRejectedValue(new Error("Reset unavailable"));
    await open();
    fireEvent.click(screen.getByRole("button", { name: "Clear" }));
    await waitFor(() => expect(store.getSession(id)?.messages).toEqual([]));
    expect(console.error).toHaveBeenCalledWith(
      "[AiAssistant] Clear reset failed",
      expect.any(Error),
    );
  });
});

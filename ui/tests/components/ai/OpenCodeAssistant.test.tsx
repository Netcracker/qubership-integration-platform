/** @jest-environment jsdom */
import { act, fireEvent, render, screen } from "@testing-library/react";
import { useOpenCodeChat } from "../../../src/ai/opencode/useOpenCodeChat.ts";
import { OpenCodeAssistant } from "../../../src/components/ai/opencode/OpenCodeAssistant.tsx";

jest.mock("../../../src/ai/opencode/useOpenCodeChat.ts", () => ({
  useOpenCodeChat: jest.fn(),
}));
jest.mock("../../../src/appConfig.ts", () => ({
  getConfig: () => ({}),
  onConfigChange: () => () => {},
}));
jest.mock("../../../src/icons/IconProvider.tsx", () => ({
  OverridableIcon: () => null,
}));
jest.mock("../../../src/components/ai/opencode/OpenCodeTranscript.tsx", () => ({
  OpenCodeTranscript: () => null,
}));

beforeAll(() => {
  Object.defineProperty(window, "matchMedia", {
    writable: true,
    value: () => ({
      matches: false,
      addListener: jest.fn(),
      removeListener: jest.fn(),
      addEventListener: jest.fn(),
      removeEventListener: jest.fn(),
    }),
  });
});

it("should retain a draft edited while the previous message is being accepted", async () => {
  let accept!: (sent: boolean) => void;
  const send = jest.fn().mockImplementation(
    () =>
      new Promise<boolean>((resolve) => {
        accept = resolve;
      }),
  );
  jest.mocked(useOpenCodeChat).mockReturnValue({
    connected: true,
    ready: true,
    busy: false,
    connecting: false,
    error: null,
    serverUrl: "http://localhost:4096",
    sessionID: "s",
    sessions: [],
    messages: [],
    permissions: [],
    questions: [],
    connect: jest.fn(),
    selectSession: jest.fn(),
    newSession: jest.fn(),
    clear: jest.fn(),
    abort: jest.fn(),
    replyPermission: jest.fn(),
    replyQuestion: jest.fn(),
    send,
  });
  render(<OpenCodeAssistant />);
  fireEvent.click(screen.getByRole("button", { name: "OpenCode" }));
  const input = await screen.findByPlaceholderText("Ask OpenCode…");
  const attachmentInput = document.querySelector('input[type="file"]');
  const firstFile = new File(["first"], "first.txt", { type: "text/plain" });
  fireEvent.change(attachmentInput!, { target: { files: [firstFile] } });
  fireEvent.change(input, { target: { value: "First message" } });
  fireEvent.click(screen.getByRole("button", { name: "Send" }));
  expect(send).toHaveBeenCalledWith("First message", [firstFile]);
  fireEvent.change(input, { target: { value: "Next message" } });
  const nextFile = new File(["next"], "next.txt", { type: "text/plain" });
  fireEvent.change(attachmentInput!, { target: { files: [nextFile] } });
  await act(async () => accept(true));
  expect(input).toHaveValue("Next message");
  expect(screen.queryByText("first.txt")).not.toBeInTheDocument();
  expect(screen.getByText("next.txt")).toBeInTheDocument();
});

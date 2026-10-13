/** @jest-environment jsdom */
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import type {
  PermissionRequest,
  QuestionRequest,
} from "@opencode-ai/sdk/v2/client";
import {
  OpenCodePermissionCard,
  OpenCodeQuestionCard,
} from "../../../src/components/ai/opencode/OpenCodeCards.tsx";

beforeAll(() => {
  Object.defineProperty(window, "matchMedia", {
    writable: true,
    value: (query: string) => ({
      matches: false,
      media: query,
      addListener: jest.fn(),
      removeListener: jest.fn(),
      addEventListener: jest.fn(),
      removeEventListener: jest.fn(),
    }),
  });
});

const permission: PermissionRequest = {
  id: "p",
  sessionID: "s",
  permission: "bash",
  patterns: ["echo test"],
  always: [],
  metadata: {},
};
const question: QuestionRequest = {
  id: "q",
  sessionID: "s",
  questions: [
    {
      header: "Options",
      question: "Choose",
      multiple: true,
      custom: true,
      options: [
        { label: "One", description: "First option" },
        { label: "Two", description: "Second option" },
      ],
    },
  ],
};

it("should send the native permission reply and keep failed requests actionable", async () => {
  const reply = jest
    .fn()
    .mockRejectedValueOnce(new Error("Reply failed"))
    .mockResolvedValue(undefined);
  render(
    <OpenCodePermissionCard
      request={permission}
      disabled={false}
      onReply={reply}
    />,
  );
  fireEvent.click(screen.getByRole("button", { name: "Allow once" }));
  await screen.findByText("Reply failed");
  expect(reply).toHaveBeenCalledWith("p", "once");
  expect(screen.getByRole("button", { name: "Allow once" })).toBeEnabled();
  fireEvent.click(screen.getByRole("button", { name: "Reject" }));
  await waitFor(() => expect(reply).toHaveBeenLastCalledWith("p", "reject"));
});

it("should combine multiple choices with a custom answer", async () => {
  const reply = jest.fn().mockResolvedValue(undefined);
  render(
    <OpenCodeQuestionCard
      request={question}
      disabled={false}
      onReply={reply}
    />,
  );
  expect(screen.getByRole("button", { name: "Submit" })).toBeDisabled();
  fireEvent.click(screen.getByRole("checkbox", { name: /One/ }));
  fireEvent.change(screen.getByLabelText("Your answer: Options"), {
    target: { value: "  Custom answer  " },
  });
  fireEvent.click(screen.getByRole("button", { name: "Submit" }));
  await waitFor(() =>
    expect(reply).toHaveBeenCalledWith("q", [["One", "Custom answer"]]),
  );
});

it("should honor single-choice questions that disallow custom answers", async () => {
  const reply = jest.fn().mockResolvedValue(undefined);
  render(
    <OpenCodeQuestionCard
      request={{
        ...question,
        questions: [
          { ...question.questions[0], multiple: false, custom: false },
        ],
      }}
      disabled={false}
      onReply={reply}
    />,
  );
  expect(
    screen.queryByLabelText("Your answer: Options"),
  ).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole("radio", { name: /Two/ }));
  fireEvent.click(screen.getByRole("button", { name: "Submit" }));
  await waitFor(() => expect(reply).toHaveBeenCalledWith("q", [["Two"]]));
});

it("should clear a single choice when a custom answer replaces it", async () => {
  const reply = jest.fn().mockResolvedValue(undefined);
  render(
    <OpenCodeQuestionCard
      request={{
        ...question,
        questions: [{ ...question.questions[0], multiple: false }],
      }}
      disabled={false}
      onReply={reply}
    />,
  );
  const option = screen.getByRole("radio", { name: /One/ });
  fireEvent.click(option);
  fireEvent.change(screen.getByLabelText("Your answer: Options"), {
    target: { value: "Custom answer" },
  });
  expect(option).not.toBeChecked();
  fireEvent.click(screen.getByRole("button", { name: "Submit" }));
  await waitFor(() =>
    expect(reply).toHaveBeenCalledWith("q", [["Custom answer"]]),
  );
});

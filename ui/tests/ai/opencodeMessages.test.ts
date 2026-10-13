import type { Event, TextPart, UserMessage } from "@opencode-ai/sdk/v2/client";
import { updateMessages } from "../../src/ai/opencode/messages.ts";

const part: TextPart = {
  id: "p",
  messageID: "m",
  sessionID: "s",
  type: "text",
  text: "Hello",
};
const info: UserMessage = {
  id: "m",
  sessionID: "s",
  role: "user",
  time: { created: 1 },
  agent: "build",
  model: { providerID: "test", modelID: "mock" },
};
const snapshot: Event = {
  id: "e1",
  type: "message.part.updated",
  properties: { sessionID: "s", part, time: 1 },
};

it("should preserve parts when they arrive before message metadata", () => {
  const state = updateMessages([], snapshot, "s");
  expect(
    updateMessages(
      state,
      {
        id: "e2",
        type: "message.updated",
        properties: { sessionID: "s", info },
      },
      "s",
    ),
  ).toEqual([{ id: "m", info, parts: [part] }]);
});

it("should replace snapshots and append deltas without duplicating parts", () => {
  let state = updateMessages([], snapshot, "s");
  state = updateMessages(state, snapshot, "s");
  state = updateMessages(
    state,
    {
      id: "e2",
      type: "message.part.delta",
      properties: {
        sessionID: "s",
        messageID: "m",
        partID: "p",
        field: "text",
        delta: " world",
      },
    },
    "s",
  );
  expect(state[0].parts).toEqual([{ ...part, text: "Hello world" }]);
  state = updateMessages(
    state,
    {
      ...snapshot,
      properties: {
        ...snapshot.properties,
        part: { ...part, text: "Hello world" },
      },
    },
    "s",
  );
  expect(state[0].parts).toHaveLength(1);
  expect(state[0].parts[0]).toMatchObject({ text: "Hello world" });
});

it("should ignore another session and remove only the targeted part", () => {
  const state = updateMessages([], snapshot, "s");
  expect(updateMessages(state, snapshot, "other")).toBe(state);
  expect(
    updateMessages(
      state,
      {
        id: "e3",
        type: "message.part.removed",
        properties: { sessionID: "s", messageID: "m", partID: "p" },
      },
      "s",
    )[0].parts,
  ).toEqual([]);
});

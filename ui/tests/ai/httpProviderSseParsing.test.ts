import { describe, it, expect } from "@jest/globals";
import { parseCipSseBlock } from "../../src/ai/modelProviders/sseParsing.ts";

describe("parseCipSseBlock", () => {
  it("parses meta with conversationId", () => {
    const chunks = parseCipSseBlock(
      'event: meta\ndata: {"conversationId":"conv-abc"}\n',
    );
    expect(chunks).toEqual([
      { type: "meta", conversationId: "conv-abc" },
    ]);
  });

  it("parses token as delta", () => {
    const chunks = parseCipSseBlock("event: token\ndata: Hello\n");
    expect(chunks).toEqual([{ type: "delta", contentDelta: "Hello" }]);
  });

  it("parses step replace-by-id payload", () => {
    const chunks = parseCipSseBlock(
      'event: step\ndata: {"id":"pipeline:validate","kind":"pipeline","status":"running","label":"validate"}\n',
    );
    expect(chunks).toEqual([
      {
        type: "step",
        step: {
          id: "pipeline:validate",
          kind: "pipeline",
          status: "running",
          label: "validate",
        },
      },
    ]);
  });

  it("parses hitl without options", () => {
    const chunks = parseCipSseBlock(
      'event: hitl\ndata: {"checkpointId":"cp-1","question":"Which system?"}\n',
    );
    expect(chunks).toEqual([
      {
        type: "hitl",
        hitl: { checkpointId: "cp-1", question: "Which system?" },
      },
    ]);
    const hitl = chunks[0];
    if (hitl.type === "hitl") {
      expect(hitl.hitl).not.toHaveProperty("options");
    }
  });

  it("ignores legacy progress events", () => {
    const chunks = parseCipSseBlock(
      'event: progress\ndata: {"message":"working"}\n',
    );
    expect(chunks).toEqual([]);
  });

  it("ignores legacy hitl_checkpoint events", () => {
    const chunks = parseCipSseBlock(
      'event: hitl_checkpoint\ndata: {"checkpointId":"x","question":"Y?","options":["Yes","No"]}\n',
    );
    expect(chunks).toEqual([]);
  });

  it("parses done and error", () => {
    expect(parseCipSseBlock("event: done\ndata: conv-1\n")).toEqual([
      { type: "done", conversationId: "conv-1", finishReason: "stop" },
    ]);
    expect(parseCipSseBlock("event: error\ndata: boom\n")).toEqual([
      { type: "error", errorMessage: "boom" },
    ]);
  });
});

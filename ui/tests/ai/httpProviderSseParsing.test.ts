import { describe, it, expect } from "@jest/globals";
import {
  parseCipSseBlock,
  splitSseFrames,
} from "../../src/ai/modelProviders/sseParsing.ts";

describe("parseCipSseBlock", () => {
  it("parses meta with conversationId", () => {
    const chunks = parseCipSseBlock(
      'event: meta\ndata: {"conversationId":"conv-abc"}\n',
    );
    expect(chunks).toEqual([{ type: "meta", conversationId: "conv-abc" }]);
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

  it("parses an approval decision with its binding and actions", () => {
    const chunks = parseCipSseBlock(
      'event: decision\ndata: {"id":"approve:sha256:abc","kind":"approve","question":"Approve the plan?",' +
        '"revision":4,"actions":["approve","request-changes"],"artifactType":"implementation-plan",' +
        '"artifactHash":"sha256:abc"}\n',
    );
    expect(chunks).toEqual([
      {
        type: "decision",
        decision: {
          id: "approve:sha256:abc",
          kind: "approve",
          question: "Approve the plan?",
          revision: 4,
          actions: ["approve", "request-changes"],
          artifactType: "implementation-plan",
          artifactHash: "sha256:abc",
        },
      },
    ]);
  });

  it("parses contextual recovery metadata without deriving it from the summary", () => {
    const chunks = parseCipSseBlock(
      'event: decision\ndata: {"id":"clarify:7","kind":"clarify","question":"The provider paused requests.",' +
        '"revision":7,"actions":["retry-creation","stop-with-report"],"recovery":{' +
        '"category":"temporary-technical-failure","title":"Creation paused temporarily",' +
        '"summary":"The provider paused requests.","preservedWork":"Your approved requirements and plan are saved.",' +
        '"technicalDetails":"rate_limit_exceeded","retryDelayMs":2000,"runId":"run-1",' +
        '"failedStageId":"design-execution"}}\n',
    );

    expect(chunks[0]?.decision?.recovery).toEqual({
      category: "temporary-technical-failure",
      title: "Creation paused temporarily",
      summary: "The provider paused requests.",
      preservedWork: "Your approved requirements and plan are saved.",
      technicalDetails: "rate_limit_exceeded",
      retryDelayMs: 2000,
      runId: "run-1",
      failedStageId: "design-execution",
    });
  });

  it("parses a requirement-brief defect without treating stage ids as actions", () => {
    const chunks = parseCipSseBlock(
      'event: decision\ndata: {"id":"clarify:8","kind":"clarify","question":"The approved requirements need correction.",' +
        '"revision":8,"actions":["edit-requirements","stop-with-report"],"recovery":{' +
        '"category":"requirement-brief-defect","title":"Requirements need correction",' +
        '"summary":"The approved requirements need correction.","preservedWork":"Your approved product facts stay available.",' +
        '"technicalDetails":"PLAN_BLOCKER: missing quartz","runId":"run-1",' +
        '"failedStageId":"planning"}}\n',
    );

    expect(chunks[0]?.decision?.actions).toEqual([
      "edit-requirements",
      "stop-with-report",
    ]);
    expect(chunks[0]?.decision?.recovery).toEqual({
      category: "requirement-brief-defect",
      title: "Requirements need correction",
      summary: "The approved requirements need correction.",
      preservedWork: "Your approved product facts stay available.",
      technicalDetails: "PLAN_BLOCKER: missing quartz",
      runId: "run-1",
      failedStageId: "planning",
    });
  });

  it("parses a plan-artifact defect without treating stage ids as actions", () => {
    const chunks = parseCipSseBlock(
      'event: decision\ndata: {"id":"clarify:9","kind":"clarify","question":"The plan is missing information required to create the chain.",' +
        '"revision":9,"actions":["rebuild-plan","stop-with-report"],"recovery":{' +
        '"category":"plan-artifact-defect","title":"The plan cannot be used",' +
        '"summary":"The plan is missing information required to create the chain.",' +
        '"preservedWork":"Your approved requirements stay unchanged.",' +
        '"technicalDetails":"PLAN_BLOCKER: invalid graph edge","runId":"run-1",' +
        '"failedStageId":"design-execution"}}\n',
    );

    expect(chunks[0]?.decision?.actions).toEqual(["rebuild-plan", "stop-with-report"]);
    expect(chunks[0]?.decision?.recovery).toEqual({
      category: "plan-artifact-defect",
      title: "The plan cannot be used",
      summary: "The plan is missing information required to create the chain.",
      preservedWork: "Your approved requirements stay unchanged.",
      technicalDetails: "PLAN_BLOCKER: invalid graph edge",
      runId: "run-1",
      failedStageId: "design-execution",
    });
  });

  it("drops a decision when the kind is unknown", () => {
    expect(
      parseCipSseBlock('event: decision\ndata: {"id":"x","kind":"vote"}\n'),
    ).toEqual([]);
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

describe("splitSseFrames", () => {
  it("should yield a complete step frame before the stream ends when the delimiter is CRLF", () => {
    const { complete, rest } = splitSseFrames(
      'event: step\r\ndata: {"id":"skill:search","kind":"skill","status":"running","label":"search"}\r\n\r\n',
    );
    expect(complete).toHaveLength(1);
    expect(rest).toBe("");
    expect(parseCipSseBlock(complete[0])).toEqual([
      {
        type: "step",
        step: {
          id: "skill:search",
          kind: "skill",
          status: "running",
          label: "search",
        },
      },
    ]);
  });

  it("should keep a partial frame in the rest buffer until the blank line arrives", () => {
    const { complete, rest } = splitSseFrames(
      'event: step\ndata: {"id":"skill:search"',
    );
    expect(complete).toEqual([]);
    expect(rest).toContain("event: step");
  });
});

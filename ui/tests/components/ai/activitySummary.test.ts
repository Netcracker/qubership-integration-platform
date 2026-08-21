import { describe, it, expect } from "@jest/globals";
import type { ActivityStepPayload } from "../../../src/ai/modelProviders/types.ts";
import {
  attachActivityToLastAssistant,
  buildActivitySummary,
  formatActivityDuration,
  resolveActivityVisualKind,
  resolveDisplayedActivityStatus,
} from "../../../src/components/ai/activity/activitySummary.ts";

describe("formatActivityDuration", () => {
  it("should format sub-minute durations in seconds when durationMs is provided", () => {
    expect(formatActivityDuration(1200)).toBe("1.2s");
    expect(formatActivityDuration(500)).toBe("0.5s");
    expect(formatActivityDuration(10000)).toBe("10s");
  });

  it("should format minute-plus durations as m and s when durationMs is large", () => {
    expect(formatActivityDuration(65000)).toBe("1m 5s");
  });
});

describe("buildActivitySummary", () => {
  const steps: ActivityStepPayload[] = [
    {
      id: "pipeline:validate",
      kind: "pipeline",
      status: "completed",
      label: "validate",
    },
    {
      id: "tool:a",
      kind: "tool",
      status: "completed",
      label: "a",
      parentId: "pipeline:validate",
    },
    {
      id: "tool:b",
      kind: "tool",
      status: "completed",
      label: "b",
      parentId: "pipeline:validate",
    },
    {
      id: "tool:c",
      kind: "tool",
      status: "error",
      label: "c",
      parentId: "pipeline:validate",
    },
  ];

  it("should include tool count and duration when durationMs is provided", () => {
    expect(buildActivitySummary(steps, 1200)).toBe("3 tools · 1.2s");
  });

  it("should omit duration when durationMs is undefined", () => {
    expect(buildActivitySummary(steps)).toBe("3 tools");
  });

  it("should use singular tool when there is one tool", () => {
    expect(
      buildActivitySummary([
        { id: "t1", kind: "tool", status: "completed", label: "t1" },
      ]),
    ).toBe("1 tool");
  });

  it("should fall back to parent/step count when there are no tools", () => {
    expect(
      buildActivitySummary([
        { id: "s1", kind: "skill", status: "completed", label: "s1" },
      ]),
    ).toBe("1 step");
  });
});

describe("resolveActivityVisualKind", () => {
  it("should map skill and tool directly", () => {
    expect(resolveActivityVisualKind("skill")).toBe("skill");
    expect(resolveActivityVisualKind("tool")).toBe("tool");
  });

  it("should map pipeline to the api visual variant without inventing SSE fields", () => {
    expect(resolveActivityVisualKind("pipeline")).toBe("api");
  });
});

describe("resolveDisplayedActivityStatus", () => {
  const brainstorming: ActivityStepPayload = {
    id: "skill:brainstorming",
    kind: "skill",
    status: "running",
    label: "brainstorming",
  };
  const draft: ActivityStepPayload = {
    id: "tool:draft",
    kind: "tool",
    status: "completed",
    label: "captureRequirementDraft",
    parentId: "skill:brainstorming",
  };
  const analyzer: ActivityStepPayload = {
    id: "skill:cip-requirement-analyzer",
    kind: "skill",
    status: "running",
    label: "cip-requirement-analyzer",
  };

  it("should show an earlier skill as completed once a later skill has started", () => {
    expect(
      resolveDisplayedActivityStatus(brainstorming, [
        brainstorming,
        draft,
        analyzer,
      ]),
    ).toBe("completed");
  });

  it("should keep a skill running while it still has a running child", () => {
    const runningDraft: ActivityStepPayload = {
      ...draft,
      status: "running",
    };
    expect(
      resolveDisplayedActivityStatus(brainstorming, [
        brainstorming,
        runningDraft,
        analyzer,
      ]),
    ).toBe("running");
  });

  it("should keep a skill running when no later skill has started", () => {
    expect(
      resolveDisplayedActivityStatus(brainstorming, [brainstorming, draft]),
    ).toBe("running");
  });

  it("should keep a skill running when only a pipeline pulse follows it", () => {
    const working: ActivityStepPayload = {
      id: "pipeline:working",
      kind: "pipeline",
      status: "running",
      label: "Working",
    };
    expect(
      resolveDisplayedActivityStatus(brainstorming, [brainstorming, working]),
    ).toBe("running");
  });
});

describe("attachActivityToLastAssistant", () => {
  it("should attach collapsed activity snapshot to the last assistant message", () => {
    const steps: ActivityStepPayload[] = [
      { id: "t1", kind: "tool", status: "completed", label: "t1" },
    ];
    const messages = [
      { role: "user" as const, content: "hi" },
      { role: "assistant" as const, content: "hello", id: "a1" },
    ];
    const next = attachActivityToLastAssistant(messages, steps, 800);
    expect(next[1]).toMatchObject({
      id: "a1",
      content: "hello",
      activity: {
        steps,
        summary: "1 tool · 0.8s",
        collapsed: true,
      },
    });
  });

  it("should leave messages unchanged when steps are empty", () => {
    const messages = [{ role: "assistant" as const, content: "hello" }];
    expect(attachActivityToLastAssistant(messages, [])).toBe(messages);
  });
});

import { describe, it, expect } from "@jest/globals";
import { createActivityStore } from "../../../src/components/ai/activity/activityStore.ts";

describe("createActivityStore", () => {
  it("replace by id updates status in place", () => {
    const store = createActivityStore();
    store.applyStep({
      id: "pipeline:validate",
      kind: "pipeline",
      status: "running",
      label: "validate",
    });
    store.applyStep({
      id: "pipeline:validate",
      kind: "pipeline",
      status: "completed",
      label: "validate",
    });
    expect(store.getRows()).toHaveLength(1);
    expect(store.getRows()[0].status).toBe("completed");
  });

  it("markRunningCancelled only affects running rows", () => {
    const store = createActivityStore();
    store.applyStep({ id: "a", kind: "skill", status: "completed", label: "a" });
    store.applyStep({ id: "b", kind: "skill", status: "running", label: "b" });
    store.markRunningCancelled();
    expect(store.getRows().find((r) => r.id === "b")?.status).toBe("cancelled");
    expect(store.getRows().find((r) => r.id === "a")?.status).toBe("completed");
  });

  it("getOrientationLabel prefers latest running skill or pipeline", () => {
    const store = createActivityStore();
    store.applyStep({
      id: "tool:x",
      kind: "tool",
      status: "running",
      label: "x",
    });
    expect(store.getOrientationLabel()).toBeUndefined();
    store.applyStep({
      id: "pipeline:validate",
      kind: "pipeline",
      status: "running",
      label: "validate",
    });
    expect(store.getOrientationLabel()).toBe("validate");
  });

  it("reset clears rows", () => {
    const store = createActivityStore();
    store.applyStep({ id: "a", kind: "skill", status: "running", label: "a" });
    store.reset();
    expect(store.getRows()).toHaveLength(0);
  });
});

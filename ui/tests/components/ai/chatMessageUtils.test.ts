import { describe, it, expect } from "@jest/globals";
import {
  isCollapsibleChainPlanJsonBlock,
  looksLikeChainImplementationPlanJson,
} from "../../../src/components/ai/chatMessageUtils.ts";

describe("looksLikeChainImplementationPlanJson", () => {
  it("detects chain plan shaped JSON", () => {
    const json =
      '{"chain":{"name":"Example"},"elements":[{"clientId":"a","type":"http-trigger"}]}';
    expect(looksLikeChainImplementationPlanJson(json)).toBe(true);
  });

  it("rejects unrelated JSON", () => {
    expect(looksLikeChainImplementationPlanJson('{"debug": true}')).toBe(false);
  });
});

describe("isCollapsibleChainPlanJsonBlock", () => {
  const planJson =
    '{"chain":{"name":"Example"},"elements":[{"clientId":"a","type":"http-trigger"}]}';

  it("returns true for chain-plan-json language tag", () => {
    expect(isCollapsibleChainPlanJsonBlock("chain-plan-json", planJson)).toBe(
      true,
    );
  });

  it("returns true for json language when content looks like a chain plan", () => {
    expect(isCollapsibleChainPlanJsonBlock("json", planJson)).toBe(true);
  });

  it("returns false for regular json blocks", () => {
    expect(isCollapsibleChainPlanJsonBlock("json", '{"visible": true}')).toBe(
      false,
    );
    expect(isCollapsibleChainPlanJsonBlock("typescript", planJson)).toBe(false);
    expect(isCollapsibleChainPlanJsonBlock(undefined, planJson)).toBe(false);
  });
});

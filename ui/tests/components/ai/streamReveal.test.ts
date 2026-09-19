import { describe, expect, it } from "@jest/globals";
import {
  STREAM_REVEAL_CHUNK_CHARS,
  nextStreamRevealChunk,
  revealStreamText,
} from "../../../src/components/ai/streamReveal.ts";

describe("nextStreamRevealChunk", () => {
  it("should return the whole string when it fits in one frame", () => {
    expect(nextStreamRevealChunk("Ready to continue")).toEqual({
      chunk: "Ready to continue",
      rest: "",
    });
  });

  it("should break on a newline near the chunk limit", () => {
    const head = "A".repeat(STREAM_REVEAL_CHUNK_CHARS - 4);
    const pending = `${head}\nrest of the sheet`;
    const { chunk, rest } = nextStreamRevealChunk(pending);
    expect(chunk).toBe(`${head}\n`);
    expect(rest).toBe("rest of the sheet");
  });

  it("should break on a space when no newline is nearby", () => {
    const pending = `${"word ".repeat(20)}TAIL`;
    const { chunk, rest } = nextStreamRevealChunk(pending);
    expect(chunk.endsWith(" ")).toBe(true);
    expect(`${chunk}${rest}`).toBe(pending);
    expect(chunk.length).toBeGreaterThan(STREAM_REVEAL_CHUNK_CHARS / 2);
  });

  it("should hard-cut when the window has no whitespace", () => {
    const pending = "x".repeat(STREAM_REVEAL_CHUNK_CHARS + 40);
    const { chunk, rest } = nextStreamRevealChunk(pending);
    expect(chunk).toBe("x".repeat(STREAM_REVEAL_CHUNK_CHARS));
    expect(rest).toBe("x".repeat(40));
  });
});

describe("revealStreamText", () => {
  it("should write a short string in one call without waiting", async () => {
    const written: string[] = [];
    let waits = 0;
    await revealStreamText(
      "Hello",
      (chunk) => written.push(chunk),
      async () => {
        waits += 1;
      },
    );
    expect(written).toEqual(["Hello"]);
    expect(waits).toBe(0);
  });

  it("should paint a long string across frames in order", async () => {
    const pending = `${"alpha ".repeat(30)}end`;
    const written: string[] = [];
    let waits = 0;
    await revealStreamText(
      pending,
      (chunk) => written.push(chunk),
      async () => {
        waits += 1;
      },
    );
    expect(written.join("")).toBe(pending);
    expect(written.length).toBeGreaterThan(1);
    expect(waits).toBe(written.length - 1);
  });

  it("should dump the rest in one write when flush is requested", async () => {
    const pending = `${"alpha ".repeat(30)}end`;
    const written: string[] = [];
    let waits = 0;
    await revealStreamText(
      pending,
      (chunk) => written.push(chunk),
      async () => {
        waits += 1;
      },
      () => true,
    );
    expect(written).toEqual([pending]);
    expect(waits).toBe(0);
  });
});

/** Max characters painted in one frame when a token arrives as a sheet. */
export const STREAM_REVEAL_CHUNK_CHARS = 56;

/**
 * Split the next paint window on a newline or space when one is nearby.
 * Falls back to a hard cut so a long token still reveals across frames.
 */
export function nextStreamRevealChunk(pending: string): {
  chunk: string;
  rest: string;
} {
  if (pending.length <= STREAM_REVEAL_CHUNK_CHARS) {
    return { chunk: pending, rest: "" };
  }
  const search = pending.slice(0, STREAM_REVEAL_CHUNK_CHARS + 32);
  const newline = search.lastIndexOf("\n");
  const space = search.lastIndexOf(" ");
  const breakAt = newline >= STREAM_REVEAL_CHUNK_CHARS / 2 ? newline : space;
  const cut =
    breakAt >= STREAM_REVEAL_CHUNK_CHARS / 2
      ? breakAt + 1
      : STREAM_REVEAL_CHUNK_CHARS;
  return { chunk: pending.slice(0, cut), rest: pending.slice(cut) };
}

export function waitForNextPaint(): Promise<void> {
  return new Promise((resolve) => {
    if (typeof requestAnimationFrame === "function") {
      requestAnimationFrame(() => resolve());
      return;
    }
    setTimeout(resolve, 16);
  });
}

/** Paint `text` in frame-sized chunks so a dumped token reads like SSE. */
export async function revealStreamText(
  text: string,
  write: (chunk: string) => void,
  wait: () => Promise<void> = waitForNextPaint,
  shouldFlushRest: () => boolean = () => false,
): Promise<void> {
  let pending = text;
  while (pending.length > 0) {
    if (shouldFlushRest()) {
      write(pending);
      return;
    }
    const { chunk, rest } = nextStreamRevealChunk(pending);
    pending = rest;
    if (chunk) {
      write(chunk);
    }
    if (pending.length > 0) {
      await wait();
    }
  }
}

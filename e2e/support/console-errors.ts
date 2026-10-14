/** Runs `body` with `console.error` collected rather than printed, and answers what it collected. */
export async function whileCollectingErrors(body: () => Promise<void>): Promise<string[]> {
  const collected: string[] = [];
  const original = console.error;
  console.error = (...parts: unknown[]) => {
    collected.push(parts.map(String).join(" "));
  };
  try {
    await body();
  } finally {
    console.error = original;
  }
  return collected;
}

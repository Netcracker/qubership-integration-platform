// Freezes the ids the import mints so a golden file can be compared byte for byte.
//
// Three `crypto.randomUUID()` calls lie on the import path: the `importId` in `runImport`, which no
// file carries, one `specificationSources[].id` per extracted file, and the environment id in
// `EnvironmentService.createEnvironment`. One counter serves all of them, so a golden records the
// consumption order rather than hiding it behind a per-call-site stub.
//
// The clock is left alone: the three `Date.now()` calls on the path all assign to an unused `now`,
// and no `createdWhen` or `modifiedWhen` reaches a written file.

export type FrozenIds = {
  // The values handed out so far, in order, so a case can name the one it expects.
  readonly issued: readonly string[];
  restore(): void;
};

// `prefix` is the first uuid group, eight hex characters, and says in the golden that the ids are
// fixtures. Each protocol uses its own, so the goldens imported into one catalog never collide.
export function freezeSourceIds(prefix: string): FrozenIds {
  const issued: string[] = [];
  const original = globalThis.crypto.randomUUID;

  const next = () => {
    const value = `${prefix}-0000-4000-8000-${String(issued.length + 1).padStart(12, "0")}`;
    issued.push(value);
    return value as ReturnType<Crypto["randomUUID"]>;
  };

  globalThis.crypto.randomUUID = next as Crypto["randomUUID"];

  return {
    issued,
    restore: () => {
      globalThis.crypto.randomUUID = original;
    },
  };
}

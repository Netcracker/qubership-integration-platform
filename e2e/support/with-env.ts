/** Running a pure function under a chosen environment, for the specs that pin target selection. */

/** The variables that point the suite at a stack elsewhere, which a port check has to clear. */
export const URL_OVERRIDES: readonly string[] = [
  "CIP_CATALOG_URL",
  "CIP_ENGINE_URL",
  "CIP_SESSIONS_URL",
  "CIP_TESTING_SERVICE_URL",
  "CIP_PROXY_URL",
];

/** Runs `body` with the given variables set, or unset where the value is `undefined`. */
export function withEnv<T>(vars: Record<string, string | undefined>, body: () => T): T {
  const original = Object.fromEntries(Object.keys(vars).map((name) => [name, process.env[name]]));
  const assign = (values: Record<string, string | undefined>) => {
    for (const [name, value] of Object.entries(values)) {
      if (value === undefined) delete process.env[name];
      else process.env[name] = value;
    }
  };
  assign(vars);
  try {
    return body();
  } finally {
    assign(original);
  }
}

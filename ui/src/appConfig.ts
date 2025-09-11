let appNameValue: string = import.meta.env.VITE_API_APP;

/**
 * Sets the application name once on bootstrap. Subsequent reads use getAppName().
 * If name is falsy, the current value is preserved.
 */
export function setAppName(name: string | undefined | null): void {
  console.log("setAppName", name);
  if (typeof name === "string" && name.length > 0) {
    appNameValue = name;
  }
}

/**
 * Returns the current application name.
 */
export function getAppName(): string {
  return appNameValue;
}

export type AppProps = {
  appName?: string;
};

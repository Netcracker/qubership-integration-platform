let appNameValue: string = import.meta.env.VITE_API_APP;

/**
 * Sets the application name once on bootstrap. Subsequent reads use getAppName().
 * If name is falsy, the current value is preserved.
 */
function setAppName(name: string | undefined | null): void {
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

export type AppExtensionProps = {
  appName?: string;
};

export function configureAppExtension(message: AppExtensionProps) {
  setAppName(message.appName);
  console.info("Initial extension configuration succeeded");
}

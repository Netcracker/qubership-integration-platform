import { IconSet } from "./IconProvider.tsx";

let appNameValue: string = import.meta.env.VITE_API_APP;
let appIcons: IconSet = {};

/**
 * Sets the application name once on bootstrap. Subsequent reads use getAppName().
 * If name is falsy, the current value is preserved.
 */
function setAppName(name: string | undefined | null): void {
  if (typeof name === "string" && name.length > 0) {
    appNameValue = name;
  }
}

export function setIcons(icons?: IconSet) {
  if (icons) {
    appIcons = icons;
  }
}

/**
 * Returns the current application name.
 */
export function getAppName(): string {
  return appNameValue;
}

export function getIcons(): IconSet {
  return appIcons;
}

export type AppExtensionProps = {
  appName?: string;
  icons?: IconSet;
};

export function configureAppExtension(message: AppExtensionProps) {
  setAppName(message.appName);
  setIcons(message.icons);
  console.info("Initial extension configuration succeeded");
}

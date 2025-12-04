import { IconOverrides } from "./icons/IconProvider";

let appNameValue: string = import.meta.env.VITE_API_APP;
let appIcons: IconOverrides = {};

/**
 * Sets the application name once on bootstrap. Subsequent reads use getAppName().
 * If name is falsy, the current value is preserved.
 */
function setAppName(name: string | undefined | null): void {
  if (typeof name === "string" && name.length > 0) {
    appNameValue = name;
  }
}

export function setIcons(icons?: IconOverrides) {
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

export function getIcons(): IconOverrides {
  return appIcons;
}

export type AppExtensionProps = {
  appName?: string;
  icons?: IconOverrides;
};

export function configureAppExtension(message: AppExtensionProps) {
  setAppName(message.appName);
  setIcons(message.icons);
  console.info("Initial extension configuration succeeded");
}

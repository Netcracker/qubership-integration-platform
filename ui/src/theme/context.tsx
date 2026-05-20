import { createContext } from "react";
import { ThemeMode } from "./themeInit.ts";

export type ThemeContextValue = {
  theme: ThemeMode;
  onThemeChange: (theme: ThemeMode) => void;
  showThemeSwitcher: boolean;
};

export const ThemeContext = createContext<ThemeContextValue | null>(null);

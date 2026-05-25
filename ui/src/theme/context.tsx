import { createContext } from "react";
import { ThemeMode } from "./themeInit.ts";

export type ThemeContextValue = {
  theme: ThemeMode;
  onThemeChange: (theme: ThemeMode) => void;
};

export const ThemeContext = createContext<ThemeContextValue | null>(null);

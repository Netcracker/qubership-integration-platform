import { Segmented } from "antd";
import {
  applyThemeToDOM,
  getSavedTheme,
  saveTheme,
  ThemeMode,
  ThemeModeWithSystem,
  getSystemTheme,
  resetToSystemTheme,
  isAutoThemeEnabled,
} from "../theme/themeInit";
import { OverridableIcon, type IconName } from "../icons/IconProvider.tsx";
import styles from "./ThemeSwitcher.module.css";

interface ThemeSwitcherProps {
  currentTheme?: ThemeMode;
  onThemeChange?: (theme: ThemeMode) => void;
}

const ICON_STYLE = { fontSize: 15 };

// Static, prop-independent: built once so each render reuses the same nodes.
const OPTIONS: { label: string; value: ThemeModeWithSystem; icon: IconName }[] =
  [
    { label: "System", value: "system", icon: "desktop" },
    { label: "Light", value: "light", icon: "sun" },
    { label: "Dark", value: "dark", icon: "moon" },
    { label: "HC", value: "high-contrast", icon: "eye" },
  ];

const SEGMENTED_OPTIONS = OPTIONS.map(({ label, value, icon }) => ({
  value,
  label: (
    <span className={styles.option}>
      <OverridableIcon name={icon} style={ICON_STYLE} />
      <span className={styles.optionText}>{label}</span>
    </span>
  ),
}));

export const ThemeSwitcher = ({
  currentTheme,
  onThemeChange,
}: ThemeSwitcherProps) => {
  // "System" means no saved theme: the app follows the OS preference.
  const value: ThemeModeWithSystem = isAutoThemeEnabled()
    ? "system"
    : (currentTheme ?? getSavedTheme() ?? getSystemTheme());

  const handleChange = (next: ThemeModeWithSystem) => {
    if (next === "system") {
      onThemeChange?.(resetToSystemTheme());
      return;
    }
    saveTheme(next);
    applyThemeToDOM(next);
    onThemeChange?.(next);
  };

  return (
    <Segmented<ThemeModeWithSystem>
      block
      size="small"
      value={value}
      onChange={handleChange}
      options={SEGMENTED_OPTIONS}
      className={styles.themeSegmented}
    />
  );
};

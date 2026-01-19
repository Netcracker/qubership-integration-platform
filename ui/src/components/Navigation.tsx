import { Menu, Button } from "antd";
import styles from "./Navigation.module.css";
import type { MenuProps } from "antd";
import { NotificationBar } from "./notifications/NotificationBar.tsx";
import { SettingsPanel } from "./SettingsPanel.tsx";
import { OverridableIcon } from "../icons/IconProvider.tsx";
import { isDev } from "../appConfig.ts";
import { useDocumentation } from "../hooks/useDocumentation.ts";

type MenuItem = Required<MenuProps>["items"][number];

const items: MenuItem[] = [
  {
    label: <a href="/chains">Chains</a>,
    key: "chains",
    icon: <OverridableIcon name="unorderedList" />,
  },
  {
    label: <a href="/services">Services</a>,
    key: "services",
    icon: <OverridableIcon name="appstore" />,
  },
  {
    label: <a href="/admintools">Admin Tools</a>,
    key: "admintools",
    icon: <OverridableIcon name="desktop" />,
  },
  {
    label: <a href="/devtools">Dev Tools</a>,
    key: "devtools",
    icon: <OverridableIcon name="tool" />,
  },
];

interface NavigationProps {
  showThemeSwitcher?: boolean;
  currentTheme?: 'light' | 'dark' | 'high-contrast';
  onThemeChange?: (theme: 'light' | 'dark' | 'high-contrast') => void;
}

const Navigation = ({ showThemeSwitcher = false, currentTheme, onThemeChange }: NavigationProps) => {
  const devMode = isDev();
  const shouldShowDevTools = devMode;
  const { openContextDoc } = useDocumentation();

  return (
    <nav className={styles.navigation}>
      <Menu
        style={{ border: "none" }}
        items={items}
        key="menu"
        mode="horizontal"
        className={styles.menu}
      ></Menu>
      <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
        <Button
          icon={<OverridableIcon name="questionCircle" />}
          onClick={openContextDoc}
          type="text"
          title="Help"
        />
        {(showThemeSwitcher && shouldShowDevTools) && (
          <SettingsPanel currentTheme={currentTheme} onThemeChange={onThemeChange} />
        )}
        <NotificationBar />
      </div>
    </nav>
  );
};

export default Navigation;

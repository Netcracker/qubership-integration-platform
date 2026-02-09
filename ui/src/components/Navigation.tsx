import { Menu, Button } from "antd";
import { Link, useLocation } from "react-router-dom";
import styles from "./Navigation.module.css";
import type { MenuProps } from "antd";
import { NotificationBar } from "./notifications/NotificationBar.tsx";
import { SettingsPanel } from "./SettingsPanel.tsx";
import { OverridableIcon } from "../icons/IconProvider.tsx";
import { isDev } from "../appConfig.ts";
import { useDocumentation } from "../hooks/useDocumentation.ts";
import { isVsCode } from "../api/rest/vscodeExtensionApi.ts";

type MenuItem = Required<MenuProps>["items"][number];

const items: MenuItem[] = [
  {
    label: <Link to="/chains">Chains</Link>,
    key: "chains",
    icon: <OverridableIcon name="unorderedList" />,
  },
  {
    label: <Link to="/services">Services</Link>,
    key: "services",
    icon: <OverridableIcon name="appstore" />,
  },
  {
    label: <Link to="/admintools">Admin Tools</Link>,
    key: "admintools",
    icon: <OverridableIcon name="desktop" />,
  },
  {
    label: <Link to="/devtools">Dev Tools</Link>,
    key: "devtools",
    icon: <OverridableIcon name="tool" />,
  },
];

interface NavigationProps {
  showThemeSwitcher?: boolean;
  currentTheme?: "light" | "dark" | "high-contrast";
  onThemeChange?: (theme: "light" | "dark" | "high-contrast") => void;
}

const Navigation = ({
  showThemeSwitcher = false,
  currentTheme,
  onThemeChange,
}: NavigationProps) => {
  const devMode = isDev();
  const shouldShowDevTools = devMode;
  const { openContextDoc } = useDocumentation();
  const { pathname } = useLocation();
  const selectedKey = pathname.split("/")[1] || "chains";

  return (
    <nav className={styles.navigation}>
      <Menu
        style={{ border: "none" }}
        items={items}
        key="menu"
        mode="horizontal"
        className={styles.menu}
        selectedKeys={[selectedKey]}
      ></Menu>
      <div style={{ display: "flex", alignItems: "center", gap: "8px" }}>
        {!isVsCode && (
          <Button
            icon={<OverridableIcon name="questionCircle" />}
            onClick={openContextDoc}
            type="text"
            title="Help"
          />
        )}
        {showThemeSwitcher && shouldShowDevTools && (
          <SettingsPanel
            currentTheme={currentTheme}
            onThemeChange={onThemeChange}
          />
        )}
        <NotificationBar />
      </div>
    </nav>
  );
};

export default Navigation;

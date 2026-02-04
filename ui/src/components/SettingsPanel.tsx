import { useState } from "react";
import { Button, Dropdown, Space, Typography, Divider } from "antd";
import { SettingOutlined } from "@ant-design/icons";
import type { MenuProps } from "antd";
import { ThemeSwitcher } from "./ThemeSwitcher";
import { ThemeMode } from "../theme/themeInit";

const { Text } = Typography;

interface SettingsPanelProps {
  currentTheme?: ThemeMode;
  onThemeChange?: (theme: ThemeMode) => void;
}

export const SettingsPanel = ({
  currentTheme,
  onThemeChange,
}: SettingsPanelProps) => {
  const [open, setOpen] = useState(false);

  const handleOpenChange = (newOpen: boolean) => {
    setOpen(newOpen);
  };

  const menuItems: MenuProps["items"] = [
    {
      key: "theme-section",
      label: (
        <div style={{ padding: "12px 16px" }}>
          <Text
            strong
            style={{ fontSize: "14px", color: "var(--vscode-foreground)" }}
          >
            Theme Settings
          </Text>
          <Divider style={{ margin: "12px 0 8px 0" }} />
          <div style={{ paddingLeft: "8px" }}>
            <ThemeSwitcher
              currentTheme={currentTheme}
              onThemeChange={onThemeChange}
            />
          </div>
        </div>
      ),
    },
    {
      type: "divider",
    },
    {
      key: "dev-info",
      label: (
        <div style={{ padding: "8px 16px" }}>
          <Space direction="vertical" size="small" style={{ width: "100%" }}>
            <Text type="secondary" style={{ fontSize: "12px" }}>
              Development Mode
            </Text>
            <Text type="secondary" style={{ fontSize: "11px" }}>
              Theme switcher is available in development mode
            </Text>
          </Space>
        </div>
      ),
    },
  ];

  return (
    <Dropdown
      menu={{ items: menuItems }}
      trigger={["click"]}
      open={open}
      onOpenChange={handleOpenChange}
      placement="bottomRight"
      overlayStyle={{
        backgroundColor: "var(--vscode-dropdown-background)",
        border: "1px solid var(--vscode-dropdown-border)",
        borderRadius: "8px",
        boxShadow: "0 6px 16px rgba(0, 0, 0, 0.12)",
        minWidth: "320px",
        maxWidth: "400px",
      }}
    >
      <Button
        type="text"
        icon={<SettingOutlined />}
        size="small"
        style={{
          color: "var(--vscode-foreground)",
          border: "none",
          background: "transparent",
          borderRadius: "4px",
          padding: "4px 8px",
          display: "flex",
          alignItems: "center",
          justifyContent: "center",
        }}
        title="Settings"
        onMouseEnter={(e) => {
          e.currentTarget.style.backgroundColor =
            "var(--vscode-list-hoverBackground)";
        }}
        onMouseLeave={(e) => {
          e.currentTarget.style.backgroundColor = "transparent";
        }}
      />
    </Dropdown>
  );
};

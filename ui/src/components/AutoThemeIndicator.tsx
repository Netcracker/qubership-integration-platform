import { useEffect, useState } from "react";
import { Badge, Tooltip } from "antd";
import { isAutoThemeEnabled, getSystemTheme } from "../theme/themeInit";

interface AutoThemeIndicatorProps {
  className?: string;
}

export const AutoThemeIndicator = ({ className }: AutoThemeIndicatorProps) => {
  const [isAutoEnabled, setIsAutoEnabled] = useState(isAutoThemeEnabled());
  const [systemTheme, setSystemTheme] = useState(getSystemTheme());

  useEffect(() => {
    const checkAutoTheme = () => {
      setIsAutoEnabled(isAutoThemeEnabled());
      setSystemTheme(getSystemTheme());
    };

    // Check every second for changes
    const interval = setInterval(checkAutoTheme, 1000);

    return () => clearInterval(interval);
  }, []);

  if (!isAutoEnabled) {
    return null;
  }

  return (
    <Tooltip title={`Auto theme enabled - following system (${systemTheme})`}>
      <Badge
        status="processing"
        text="Auto"
        className={className}
        style={{ fontSize: "10px", color: "#1890ff" }}
      />
    </Tooltip>
  );
};

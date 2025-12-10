import { Menu } from "antd";
import { useLocation } from "react-router-dom";
import { OverridableIcon } from "../../icons/IconProvider.tsx";

const menuItems = [
  {
    key: "#external",
    icon: <OverridableIcon name="global" />,
    label: "External",
  },
  {
    key: "#internal",
    icon: <OverridableIcon name="cloud" />,
    label: "Inner Cloud",
  },
  {
    key: "#implemented",
    icon: <OverridableIcon name="cluster" />,
    label: "Implemented",
  },
  {
    key: "#context",
    icon: <OverridableIcon name="database" />,
    label: "Context",
  },
];

export const ServiceSidebar = ({ collapsed }: { collapsed: boolean }) => {
  const location = useLocation();

  const selectedKeys = [location.hash || "#implemented"];

  const handleClick = ({ key }: { key: string }) => {
    window.location.hash = key;
  };

  return (
    <Menu
      mode="inline"
      selectedKeys={selectedKeys}
      defaultOpenKeys={[]}
      onClick={handleClick}
      items={menuItems}
      inlineCollapsed={collapsed}
      style={{ height: "100%", borderRight: 0 }}
    />
  );
};

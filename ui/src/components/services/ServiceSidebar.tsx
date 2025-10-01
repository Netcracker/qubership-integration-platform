import { Menu } from "antd";
import { useLocation } from "react-router-dom";
import { Icon } from "../../IconProvider.tsx";

const menuItems = [
  {
    key: "#external",
    icon: <Icon name="global" />,
    label: "External",
  },
  {
    key: "#internal",
    icon: <Icon name="cloud" />,
    label: "Inner Cloud",
  },
  {
    key: "#implemented",
    icon: <Icon name="cluster" />,
    label: "Implemented",
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

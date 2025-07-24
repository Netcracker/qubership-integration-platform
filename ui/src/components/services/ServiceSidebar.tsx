import {
  CloudOutlined,
  ClusterOutlined,
  GlobalOutlined,
} from "@ant-design/icons";
import { Menu } from "antd";
import { useLocation } from "react-router-dom";

const menuItems = [
  {
    key: "#external",
    icon: <GlobalOutlined/>,
    label: "External",
  },
  {
    key: "#internal",
    icon: <CloudOutlined />,
    label: "Inner Cloud",
  },
  {
    key: "#implemented",
    icon: <ClusterOutlined />,
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

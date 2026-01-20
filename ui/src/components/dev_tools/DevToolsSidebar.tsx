import { Menu } from "antd";
import { useLocation, useNavigate } from "react-router-dom";
import { OverridableIcon } from "../../icons/IconProvider.tsx";

const menuItems = [
  {
    key: "/devtools/diagnostic/validations",
    icon: <OverridableIcon name="barChart" />,
    label: "Diagnostic",
  },
];

export const DevToolsSidebar = ({ collapsed }: { collapsed: boolean }) => {
  const location = useLocation();
  const navigate = useNavigate();

  const selectedKeys = [location.pathname];

  const handleClick = ({ key }: { key: string }) => {
    if (key.startsWith("/devtools")) {
      void navigate(key);
    }
  };

  return (
    <Menu
      style={{ border: "none" }}
      mode="inline"
      selectedKeys={selectedKeys}
      defaultOpenKeys={[]}
      onClick={handleClick}
      items={menuItems}
      inlineCollapsed={collapsed}
    />
  );
};

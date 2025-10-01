import { Menu } from "antd";
import { useLocation, useNavigate } from "react-router-dom";
import { Icon } from "../../IconProvider.tsx";

const menuItems = [
  {
    key: "/admintools/domains",
    icon: <Icon name="appstore" />,
    label: "Domains",
  },
  {
    key: "variables",
    icon: <Icon name="code" />,
    label: "Variables",
    children: [
      {
        key: "/admintools/variables/common",
        icon: <Icon name="table" />,
        label: "Common",
      },
      {
        key: "/admintools/variables/secured",
        icon: <Icon name="lock" />,
        label: "Secured",
      },
    ],
  },
  {
    key: "/admintools/audit",
    icon: <Icon name="audit" />,
    label: "Audit",
  },
  {
    key: "/admintools/import-instructions",
    icon: <Icon name="cloudUpload" />,
    label: "Import Instructions",
  },
  {
    key: "/admintools/sessions",
    icon: <Icon name="user" />,
    label: "Sessions",
  },
  {
    key: "/admintools/roles",
    icon: <Icon name="settings" />,
    label: "Roles",
  },
  {
    key: "/admintools/design-templates",
    icon: <Icon name="fileText" />,
    label: "Design Templates",
  },
];

export const AdminToolsSidebar = ({ collapsed }: { collapsed: boolean }) => {
  const location = useLocation();
  const navigate = useNavigate();

  const selectedKeys = [location.pathname];
  const openKeys = location.pathname.includes("variables") ? ["variables"] : [];

  const handleClick = ({ key }: { key: string }) => {
    if (key.startsWith("/admintools")) {
      void navigate(key);
    }
  };

  return (
    <Menu
      style={{ border: "none" }}
      mode="inline"
      selectedKeys={selectedKeys}
      defaultOpenKeys={collapsed ? [] : openKeys}
      onClick={handleClick}
      items={menuItems}
      inlineCollapsed={collapsed}
    />
  );
};

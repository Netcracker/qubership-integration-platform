import { Menu } from "antd";
import { useLocation, useNavigate } from "react-router-dom";
import { OverridableIcon } from "../../icons/IconProvider.tsx";

const menuItems = [
  {
    key: "/admintools/domains",
    icon: <OverridableIcon name="appstore" />,
    label: "Domains",
  },
  {
    key: "variables",
    icon: <OverridableIcon name="code" />,
    label: "Variables",
    children: [
      {
        key: "/admintools/variables/common",
        icon: <OverridableIcon name="table" />,
        label: "Common",
      },
      {
        key: "/admintools/variables/secured",
        icon: <OverridableIcon name="lock" />,
        label: "Secured",
      },
    ],
  },
  {
    key: "/admintools/audit",
    icon: <OverridableIcon name="audit" />,
    label: "Audit",
  },
  {
    key: "/admintools/import-instructions",
    icon: <OverridableIcon name="cloudUpload" />,
    label: "Import Instructions",
  },
  {
    key: "/admintools/sessions",
    icon: <OverridableIcon name="user" />,
    label: "Sessions",
  },
  {
    key: "/admintools/roles",
    icon: <OverridableIcon name="settings" />,
    label: "Roles",
  },
  {
    key: "/admintools/design-templates",
    icon: <OverridableIcon name="fileText" />,
    label: "Design Templates",
  },
  {
    key: "/admintools/exchanges",
    icon: <OverridableIcon name="unorderedList" />,
    label: "Live Exchanges",
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

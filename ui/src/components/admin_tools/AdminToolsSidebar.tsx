import {
  AppstoreOutlined,
  FileTextOutlined,
  LockOutlined,
  SettingOutlined,
  TableOutlined,
  UserOutlined,
  CloudUploadOutlined,
  AuditOutlined,
  CodeOutlined,
} from "@ant-design/icons";
import { Menu } from "antd";
import { useLocation, useNavigate } from "react-router-dom";

const menuItems = [
  {
    key: "/admintools/domains",
    icon: <AppstoreOutlined />,
    label: "Domains",
  },
  {
    key: "variables",
    icon: <CodeOutlined />,
    label: "Variables",
    children: [
      {
        key: "/admintools/variables/common",
        icon: <TableOutlined />,
        label: "Common",
      },
      {
        key: "/admintools/variables/secured",
        icon: <LockOutlined />,
        label: "Secured",
      },
    ],
  },
  {
    key: "/admintools/audit",
    icon: <AuditOutlined />,
    label: "Audit",
  },
  {
    key: "/admintools/import-instructions",
    icon: <CloudUploadOutlined />,
    label: "Import Instructions",
  },
  {
    key: "/admintools/sessions",
    icon: <UserOutlined />,
    label: "Sessions",
  },
  {
    key: "/admintools/roles",
    icon: <SettingOutlined />,
    label: "Roles",
  },
  {
    key: "/admintools/design-templates",
    icon: <FileTextOutlined />,
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
      navigate(key);
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

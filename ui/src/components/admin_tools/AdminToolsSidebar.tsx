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
    key: "/admin-tools/domains",
    icon: <AppstoreOutlined />,
    label: "Domains",
  },
  {
    key: "variables",
    icon: <CodeOutlined />,
    label: "Variables",
    children: [
      {
        key: "/admin-tools/variables/common",
        icon: <TableOutlined />,
        label: "Common",
      },
      {
        key: "/admin-tools/variables/secured",
        icon: <LockOutlined />,
        label: "Secured",
      },
    ],
  },
  {
    key: "/admin-tools/audit",
    icon: <AuditOutlined />,
    label: "Audit",
  },
  {
    key: "/admin-tools/import-instructions",
    icon: <CloudUploadOutlined />,
    label: "Import Instructions",
  },
  {
    key: "/admin-tools/sessions",
    icon: <UserOutlined />,
    label: "Sessions",
  },
  {
    key: "/admin-tools/roles",
    icon: <SettingOutlined />,
    label: "Roles",
  },
  {
    key: "/admin-tools/design-templates",
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
    if (key.startsWith("/admin-tools")) {
      navigate(key);
    }
  };

  return (
    <Menu
      mode="inline"
      selectedKeys={selectedKeys}
      defaultOpenKeys={collapsed ? [] : openKeys}
      onClick={handleClick}
      items={menuItems}
      inlineCollapsed={collapsed}
    />
  );
};
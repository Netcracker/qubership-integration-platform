import { Menu } from "antd";
import { useLocation, useNavigate } from "react-router-dom";
import { useState, useEffect } from "react";
import { OverridableIcon } from "../../icons/IconProvider.tsx";

const menuItems = [
  {
    key: "maas",
    icon: <OverridableIcon name="deploymentUnit" />,
    label: "Maas",
    children: [
      {
        key: "/devtools/maas/kafka",
        icon: <OverridableIcon name="deploymentUnit" />,
        label: "Kafka",
      },
      {
        key: "/devtools/maas/rabbitmq",
        icon: <OverridableIcon name="deploymentUnit" />,
        label: "RabbitMQ",
      },
    ],
  },
  {
    key: "/devtools/diagnostic/validations",
    icon: <OverridableIcon name="barChart" />,
    label: "Diagnostic",
  },
];

export const DevToolsSidebar = ({ collapsed }: { collapsed: boolean }) => {
  const location = useLocation();
  const navigate = useNavigate();
  const [openKeys, setOpenKeys] = useState<string[]>([]);

  const selectedKeys = [location.pathname];

  useEffect(() => {
    if (location.pathname.includes("maas")) {
      setOpenKeys(["maas"]);
    } else {
      setOpenKeys([]);
    }
  }, [location.pathname]);

  const handleClick = ({ key }: { key: string }) => {
    if (key.startsWith("/devtools")) {
      void navigate(key);
    }
  };

  const handleOpenChange = (keys: string[]) => {
    setOpenKeys(keys);
  };

  return (
    <Menu
      style={{ border: "none" }}
      mode="inline"
      selectedKeys={selectedKeys}
      openKeys={collapsed ? [] : openKeys}
      onOpenChange={handleOpenChange}
      onClick={handleClick}
      items={menuItems}
      inlineCollapsed={collapsed}
    />
  );
};

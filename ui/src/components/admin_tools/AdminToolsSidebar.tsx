import { ReactNode, useMemo } from "react";
import { Menu } from "antd";
import { useLocation, useNavigate } from "react-router-dom";
import { OverridableIcon } from "../../icons/IconProvider.tsx";
import { useTestingServiceAvailability } from "../../hooks/useTestingServiceAvailability.ts";

type SidebarMenuItem = {
  key: string;
  icon: ReactNode;
  label: ReactNode;
  children?: SidebarMenuItem[];
};

const menuItems: SidebarMenuItem[] = [
  {
    key: "/admintools/domains",
    icon: <OverridableIcon name="domains" />,
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
    icon: <OverridableIcon name="importInstructions" />,
    label: (
      <span style={{ display: "block", lineHeight: 1.3 }}>
        Import
        <br />
        Instructions
      </span>
    ),
  },
  {
    key: "/admintools/sessions",
    icon: <OverridableIcon name="sessions" />,
    label: "Sessions",
  },
  {
    key: "/admintools/access-control",
    icon: <OverridableIcon name="accessControl" />,
    label: "Access Control",
  },
  {
    key: "/admintools/detailed-design/templates",
    icon: <OverridableIcon name="fileText" />,
    label: "Design Templates",
  },
  {
    key: "/admintools/exchanges",
    icon: <OverridableIcon name="liveExchanges" />,
    label: "Live Exchanges",
  },
];

const testingMenuItem: SidebarMenuItem = {
  key: "testing",
  icon: <OverridableIcon name="testing" />,
  label: "Testing",
  children: [
    {
      key: "/admintools/testing/test-cases",
      icon: <OverridableIcon name="checkSquare" />,
      label: "Test Cases",
    },
    {
      key: "/admintools/testing/endpoint-mocks",
      icon: <OverridableIcon name="api" />,
      label: "Endpoint Mocks",
    },
    {
      key: "/admintools/testing/test-runs",
      icon: <OverridableIcon name="carryOut" />,
      label: "Test Runs",
    },
  ],
};

/** Submenus holding the entry the current route belongs to. */
function getOpenKeys(items: SidebarMenuItem[], pathname: string): string[] {
  return items
    .filter((item) =>
      item.children?.some((child) => pathname.startsWith(child.key)),
    )
    .map((item) => item.key);
}

export const AdminToolsSidebar = ({ collapsed }: { collapsed: boolean }) => {
  const location = useLocation();
  const navigate = useNavigate();
  const { isAvailable: isTestingAvailable } = useTestingServiceAvailability();

  const items = useMemo(
    () => (isTestingAvailable ? [...menuItems, testingMenuItem] : menuItems),
    [isTestingAvailable],
  );

  const selectedKeys = [location.pathname];
  const openKeys = getOpenKeys(items, location.pathname);

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
      items={items}
      inlineCollapsed={collapsed}
    />
  );
};

import { Menu } from "antd";
import {
  Navigate,
  Outlet,
  useLocation,
  useNavigate,
  useParams,
} from "react-router";
import { PageWithSidebar } from "../PageWithSidebar.tsx";
import { OverridableIcon } from "../../icons/IconProvider.tsx";
import { useTestingServiceAvailability } from "../../hooks/useTestingServiceAvailability.ts";

/** Path the guard sends a visitor to when the testing service is not deployed. */
export const NOT_FOUND_PATH = "/not-found";

export const TESTING_SECTIONS = [
  { key: "test-cases", icon: "checkSquare", label: "Test Cases" },
  { key: "endpoint-mocks", icon: "api", label: "Endpoint Mocks" },
  { key: "test-case-runs", icon: "carryOut", label: "Test Case Runs" },
] as const;

const menuItems = TESTING_SECTIONS.map(({ key, icon, label }) => ({
  key,
  icon: <OverridableIcon name={icon} />,
  label,
}));

function getActiveSection(pathname: string): string {
  const segments = pathname.split("/");
  const index = segments.indexOf("testing");
  return segments[index + 1] ?? TESTING_SECTIONS[0].key;
}

export const TestingSidebar = ({ collapsed }: { collapsed: boolean }) => {
  const location = useLocation();
  const navigate = useNavigate();
  const { chainId } = useParams();

  const handleClick = ({ key }: { key: string }) => {
    void navigate(`/chains/${chainId}/testing/${key}`);
  };

  return (
    <Menu
      mode="inline"
      style={{ height: "100%", borderRight: 0 }}
      selectedKeys={[getActiveSection(location.pathname)]}
      onClick={handleClick}
      items={menuItems}
      inlineCollapsed={collapsed}
    />
  );
};

/** Chain-scoped shell: the section menu beside the screen the route selected. */
export const TestingLayout = () => (
  <PageWithSidebar sidebar={<TestingSidebar collapsed={false} />}>
    <Outlet />
  </PageWithSidebar>
);

/**
 * Keeps the whole testing subtree off a deployment without the service, so a
 * bookmark or a back-navigation cannot land on a screen that would fire
 * requests at nothing.
 */
export const TestingGuard = () => {
  const { isAvailable, isLoading } = useTestingServiceAvailability();

  if (isLoading) {
    return null;
  }
  return isAvailable ? <Outlet /> : <Navigate to={NOT_FOUND_PATH} replace />;
};

export default TestingLayout;

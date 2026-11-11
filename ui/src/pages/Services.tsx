import { Navigate, useLocation } from "react-router-dom";
import { ServiceSidebar } from "../components/services/detail/ServiceSidebar";
import { PageWithSidebar } from "./PageWithSidebar";
import { ServiceListPage } from "../components/services/ServiceListPage.tsx";

export const Services = () => {
  const { hash, pathname, search } = useLocation();
  if (!hash || hash === "#") {
    return <Navigate to={{ pathname, search, hash: "#external" }} replace />;
  }
  return (
    <PageWithSidebar sidebar={<ServiceSidebar collapsed={false} />}>
      <ServiceListPage />
    </PageWithSidebar>
  );
};

export default Services;

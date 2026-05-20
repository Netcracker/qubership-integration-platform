import { ServiceSidebar } from "../components/services/detail/ServiceSidebar";
import { PageWithSidebar } from "./PageWithSidebar";
import { ServiceListPage } from "../components/services/ServiceListPage.tsx";

export const Services = () => {
  return (
    <PageWithSidebar sidebar={<ServiceSidebar collapsed={false} />}>
      <ServiceListPage />
    </PageWithSidebar>
  );
};

export default Services;

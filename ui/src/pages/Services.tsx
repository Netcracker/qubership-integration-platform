import { ServiceSidebar } from "../components/services/ServiceSidebar";
import { ServicesListPage } from "../components/services/ServicesListPage.tsx";
import { PageWithSidebar } from "./PageWithSidebar";

export const Services = () => {
  return (
    <PageWithSidebar sidebar={<ServiceSidebar collapsed={false} />}>
      <ServicesListPage />
    </PageWithSidebar>
  );
};

export default Services;

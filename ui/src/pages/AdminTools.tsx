import { PageWithSidebar } from './PageWithSidebar';
import { AdminToolsSidebar } from '../components/admin_tools/AdminToolsSidebar';
import { Outlet } from 'react-router-dom';

export const AdminTools = () => (
  <PageWithSidebar sidebar={<AdminToolsSidebar collapsed={false} />}>
    <Outlet />
  </PageWithSidebar>
);

export default AdminTools; 
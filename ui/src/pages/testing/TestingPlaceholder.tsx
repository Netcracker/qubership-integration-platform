import { Outlet } from "react-router";
import { NotImplemented } from "../NotImplemented.tsx";

/** Stands in for a testing screen until the task that builds it lands. */
export const TestingPlaceholder = ({ name }: { name: string }) => (
  <>
    <NotImplemented subTitle={name} />
    <Outlet />
  </>
);

export default TestingPlaceholder;

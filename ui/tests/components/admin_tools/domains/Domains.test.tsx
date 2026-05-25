/**
 * @jest-environment jsdom
 */

jest.mock("../../../../src/components/admin_tools/domains/DomainsTable", () => ({
  __esModule: true,
  default: () => <div data-testid="domains-table-stub" />,
}));

jest.mock("../../../../src/hooks/useDomains", () => ({
  useDomains: () => ({
    domains: [],
    isLoading: false,
  }),
}));

import { render, screen } from "@testing-library/react";
import "@testing-library/jest-dom";
import { Domains } from "../../../../src/components/admin_tools/domains/Domains.tsx";
import {
  mockDomainsTablesLayoutClasses,
  mockCommonStyleContainerModule,
} from "../../../helpers/mockDomainsTablesLayout";

describe("Domains", () => {
  test("page root applies DomainsTablesLayout.pageRoot together with admin container", () => {
    const { container } = render(<Domains />);

    const root = container.firstElementChild;
    expect(root).toBeTruthy();
    expect(root).toHaveClass(mockCommonStyleContainerModule.default.container);
    expect(root).toHaveClass(mockDomainsTablesLayoutClasses.pageRoot);
    expect(screen.getByTestId("domains-table-stub")).toBeInTheDocument();
  });
});

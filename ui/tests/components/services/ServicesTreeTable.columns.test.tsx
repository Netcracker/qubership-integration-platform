/**
 * @jest-environment jsdom
 */
import { describe, it, expect, jest } from "@jest/globals";
import { render, screen } from "@testing-library/react";
import "@testing-library/jest-dom";

jest.mock("../../../src/api/api", () => ({ api: {} }));

jest.mock("../../../src/api/rest/vscodeExtensionApi", () => ({
  isVsCode: false,
  VSCodeExtensionApi: class {},
}));

jest.mock("../../../src/icons/IconProvider.tsx", () => ({
  OverridableIcon: ({ name }: { name: string }) => (
    <span data-testid={`icon-${name}`}>{name}</span>
  ),
}));

import {
  allServicesTreeTableColumns,
  ServiceEntity,
} from "../../../src/components/services/ServicesTreeTable";
import {
  IntegrationSystem,
  IntegrationSystemType,
  SpecificationGroup,
} from "../../../src/api/apiTypes";

const renderUsedBy = (record: ServiceEntity) => {
  const column = allServicesTreeTableColumns.find(
    (col) => col.key === "usedBy",
  );
  render(<>{column?.render?.(undefined, record, 0)}</>);
};

const service = (chains?: { id: string; name: string }[]) =>
  ({
    id: "s1",
    name: "Payments",
    type: IntegrationSystemType.EXTERNAL,
    chains,
  }) as unknown as IntegrationSystem;

const specificationGroup = (chains?: { id: string; name: string }[]) =>
  ({
    id: "g1",
    name: "Payments API",
    systemId: "s1",
    synchronization: false,
    specifications: [],
    chains,
  }) as unknown as SpecificationGroup;

describe("Used by column", () => {
  it("shows the chain usage of a service, like the specification group below it", () => {
    renderUsedBy(service([{ id: "c1", name: "Alpha" }]));
    expect(screen.getByText(/1 chain/)).toBeInTheDocument();
  });

  it("shows the empty state for a service no chain uses", () => {
    renderUsedBy(service([]));
    expect(screen.getByText("No chains")).toBeInTheDocument();
  });

  it("still shows the chain usage of a specification group", () => {
    renderUsedBy(specificationGroup([{ id: "c1", name: "Alpha" }]));
    expect(screen.getByText(/1 chain/)).toBeInTheDocument();
  });
});

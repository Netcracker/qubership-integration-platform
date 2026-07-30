/**
 * @jest-environment jsdom
 */
import type { ComponentProps } from "react";
import { describe, it, expect, jest, beforeEach } from "@jest/globals";
import { render, waitFor } from "@testing-library/react";
import "@testing-library/jest-dom";

Object.defineProperty(globalThis, "matchMedia", {
  writable: true,
  value: (query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: () => {},
    removeListener: () => {},
    addEventListener: () => {},
    removeEventListener: () => {},
    dispatchEvent: () => false,
  }),
});

const getApiSpecifications = jest.fn<() => Promise<unknown>>();
const getLatestApiSpecification = jest.fn<() => Promise<unknown>>();

jest.mock("../../../../../src/api/api.ts", () => ({
  api: {
    getApiSpecifications: (...a: unknown[]) =>
      getApiSpecifications(...(a as [])),
    getLatestApiSpecification: (...a: unknown[]) =>
      getLatestApiSpecification(...(a as [])),
  },
}));

const requestFailed = jest.fn();
// One frozen instance, not a fresh object per call: the component lists the notification service
// among its effect dependencies, so a new identity on every render re-runs the load forever.
const notificationServiceStub = {
  requestFailed,
  errorWithDetails: jest.fn(),
  info: jest.fn(),
  warning: jest.fn(),
};
jest.mock("../../../../../src/hooks/useNotificationService.tsx", () => ({
  useNotificationService: () => notificationServiceStub,
}));

import SpecificationField from "../../../../../src/components/modal/chain_element/field/select/SpecificationField";

const updateContext = jest.fn();

type SpecificationFieldProps = ComponentProps<typeof SpecificationField>;

function makeProps(
  overrides: Record<string, unknown> = {},
): SpecificationFieldProps {
  return {
    schema: { title: "API" },
    uiSchema: {},
    formData: undefined,
    registry: {
      formContext: {
        integrationSystemId: "sys-1",
        integrationSpecificationGroupId: undefined,
        updateContext,
      },
    },
    ...overrides,
  } as unknown as SpecificationFieldProps;
}

/**
 * The field resolves which group an API belongs to, because selecting an API has to update the
 * group in the form context too. That map is built from the loaded groups, so the load path and its
 * failure branch are what matter here.
 */
describe("SpecificationField", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    getApiSpecifications.mockResolvedValue([]);
    getLatestApiSpecification.mockResolvedValue({ id: "m1" });
  });

  it("should load the API groups of the service in the form context", async () => {
    getApiSpecifications.mockResolvedValue([
      {
        id: "g1",
        name: "Group one",
        specifications: [{ id: "m1", name: "A" }],
      },
    ]);

    render(<SpecificationField {...makeProps()} />);

    await waitFor(() => {
      expect(getApiSpecifications).toHaveBeenCalledWith("sys-1");
    });
  });

  it("should preselect the latest API when the field has no value yet", async () => {
    getApiSpecifications.mockResolvedValue([
      {
        id: "g1",
        name: "Group one",
        specifications: [{ id: "m1", name: "A" }],
      },
    ]);

    render(<SpecificationField {...makeProps()} />);

    await waitFor(() => {
      expect(getLatestApiSpecification).toHaveBeenCalledWith("sys-1");
    });
  });

  it("should resolve the owning group of the preselected API into the form context", async () => {
    getApiSpecifications.mockResolvedValue([
      {
        id: "g1",
        name: "Group one",
        specifications: [
          { id: "m1", name: "A" },
          { id: "m2", name: "B" },
        ],
      },
      {
        id: "g2",
        name: "Group two",
        specifications: [{ id: "m3", name: "C" }],
      },
    ]);
    getLatestApiSpecification.mockResolvedValue({ id: "m3" });

    render(<SpecificationField {...makeProps()} />);

    await waitFor(() => {
      expect(updateContext).toHaveBeenCalledWith(
        expect.objectContaining({
          integrationSpecificationId: "m3",
          integrationSpecificationGroupId: "g2",
        }),
      );
    });
  });

  it("should not preselect anything when the field already has a value", async () => {
    render(<SpecificationField {...makeProps({ formData: "m9" })} />);

    await waitFor(() => {
      expect(getApiSpecifications).toHaveBeenCalled();
    });
    expect(getLatestApiSpecification).not.toHaveBeenCalled();
  });

  it("should skip loading when the form context carries no service", async () => {
    render(
      <SpecificationField
        {...makeProps({
          registry: {
            formContext: { integrationSystemId: undefined, updateContext },
          },
        })}
      />,
    );

    await waitFor(() => {
      expect(getApiSpecifications).not.toHaveBeenCalled();
    });
    expect(requestFailed).not.toHaveBeenCalled();
  });

  it("should report a failed group load instead of throwing", async () => {
    getApiSpecifications.mockRejectedValue(new Error("boom"));

    render(<SpecificationField {...makeProps()} />);

    await waitFor(() => {
      expect(requestFailed).toHaveBeenCalledWith(
        "Failed to load API groups",
        expect.any(Error),
      );
    });
  });
});

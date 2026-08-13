/**
 * @jest-environment jsdom
 */
import { renderHook, act } from "@testing-library/react";
import { useLabelsForm } from "../../src/components/services/useLabelsForm";
import type { IntegrationSystem } from "../../src/api/apiTypes";

describe("useLabelsForm", () => {
  it("decodes stored description and labels into form state", () => {
    const setFieldsValue = jest.fn();
    const form = { setFieldsValue } as never;

    const { result } = renderHook(() => useLabelsForm(form));

    const system = {
      name: "svc",
      type: "EXTERNAL",
      description: "&lt;h1&gt;test&lt;/h1&gt;",
      labels: [
        { name: "&lt;t&gt;", technical: true },
        { name: "&lt;u&gt;", technical: false },
      ],
    } as IntegrationSystem;

    act(() => {
      result.current.onSetLabelsAndForm(system);
    });

    expect(result.current.technicalLabels).toEqual(["<t>"]);
    expect(result.current.userLabels).toEqual(["<u>"]);
    expect(setFieldsValue).toHaveBeenCalledWith(
      expect.objectContaining({
        description: "<h1>test</h1>",
        labels: ["<t>", "<u>"],
      }),
    );
  });

  it("handles missing labels without a form instance", () => {
    const { result } = renderHook(() => useLabelsForm(null));

    act(() => {
      result.current.onSetLabelsAndForm({
        name: "svc",
        type: "EXTERNAL",
      } as IntegrationSystem);
    });

    expect(result.current.technicalLabels).toEqual([]);
    expect(result.current.userLabels).toEqual([]);
  });
});

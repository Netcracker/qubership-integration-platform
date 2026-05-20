/**
 * @jest-environment jsdom
 */

import type { MouseEvent } from "react";
import {
  focusSvgEventTarget,
  handleMiddlePanelConnectionSvgKeyDown,
} from "../../../src/components/mapper/mappingGraphMiddlePanelDomHandlers.ts";

describe("mappingGraphMiddlePanelDomHandlers", () => {
  test("handleMiddlePanelConnectionSvgKeyDown runs delete and clear for Delete", () => {
    const preventDefault = jest.fn();
    const stopPropagation = jest.fn();
    const deleteSelectedConnections = jest.fn();
    const clearSelection = jest.fn();

    handleMiddlePanelConnectionSvgKeyDown(
      {
        key: "Delete",
        preventDefault,
        stopPropagation,
      },
      { deleteSelectedConnections, clearSelection },
    );

    expect(preventDefault).toHaveBeenCalledTimes(1);
    expect(stopPropagation).toHaveBeenCalledTimes(1);
    expect(deleteSelectedConnections).toHaveBeenCalledTimes(1);
    expect(clearSelection).toHaveBeenCalledTimes(1);
  });

  test("handleMiddlePanelConnectionSvgKeyDown runs delete and clear for Backspace", () => {
    const preventDefault = jest.fn();
    const stopPropagation = jest.fn();
    const deleteSelectedConnections = jest.fn();
    const clearSelection = jest.fn();

    handleMiddlePanelConnectionSvgKeyDown(
      {
        key: "Backspace",
        preventDefault,
        stopPropagation,
      },
      { deleteSelectedConnections, clearSelection },
    );

    expect(preventDefault).toHaveBeenCalledTimes(1);
    expect(deleteSelectedConnections).toHaveBeenCalledTimes(1);
    expect(clearSelection).toHaveBeenCalledTimes(1);
  });

  test("handleMiddlePanelConnectionSvgKeyDown ignores other keys", () => {
    const preventDefault = jest.fn();
    const deleteSelectedConnections = jest.fn();
    const clearSelection = jest.fn();

    handleMiddlePanelConnectionSvgKeyDown(
      {
        key: "a",
        preventDefault,
        stopPropagation: jest.fn(),
      },
      { deleteSelectedConnections, clearSelection },
    );

    expect(preventDefault).not.toHaveBeenCalled();
    expect(deleteSelectedConnections).not.toHaveBeenCalled();
    expect(clearSelection).not.toHaveBeenCalled();
  });

  test("focusSvgEventTarget focuses currentTarget", () => {
    const focus = jest.fn();
    const event = {
      currentTarget: { focus },
    } as unknown as MouseEvent<SVGElement>;

    focusSvgEventTarget(event);

    expect(focus).toHaveBeenCalledTimes(1);
  });
});

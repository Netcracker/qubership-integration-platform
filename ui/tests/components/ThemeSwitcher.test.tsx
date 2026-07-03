/** @jest-environment jsdom */
import { describe, it, expect, jest, beforeEach } from "@jest/globals";
import { render, screen, fireEvent } from "@testing-library/react";
import "@testing-library/jest-dom";

const themeInit = {
  applyThemeToDOM: jest.fn(),
  getSavedTheme: jest.fn(() => "light"),
  saveTheme: jest.fn(),
  getSystemTheme: jest.fn(() => "light"),
  resetToSystemTheme: jest.fn(() => "light"),
  isAutoThemeEnabled: jest.fn(() => false),
};

jest.mock("../../src/theme/themeInit", () => themeInit);
jest.mock("../../src/icons/IconProvider.tsx", () => ({
  OverridableIcon: () => null,
}));

import { ThemeSwitcher } from "../../src/components/ThemeSwitcher";

describe("ThemeSwitcher", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    themeInit.isAutoThemeEnabled.mockReturnValue(false);
    themeInit.getSavedTheme.mockReturnValue("light");
    themeInit.resetToSystemTheme.mockReturnValue("light");
  });

  it("should save and apply the theme when a concrete option is clicked", () => {
    const onThemeChange = jest.fn();
    render(<ThemeSwitcher onThemeChange={onThemeChange} />);

    fireEvent.click(screen.getByTitle("Dark"));

    expect(themeInit.saveTheme).toHaveBeenCalledWith("dark");
    expect(themeInit.applyThemeToDOM).toHaveBeenCalledWith("dark");
    expect(onThemeChange).toHaveBeenCalledWith("dark");
  });

  it("should reset to system theme when the System option is clicked", () => {
    const onThemeChange = jest.fn();
    render(<ThemeSwitcher currentTheme="dark" onThemeChange={onThemeChange} />);

    fireEvent.click(screen.getByTitle("System"));

    expect(themeInit.resetToSystemTheme).toHaveBeenCalled();
    expect(themeInit.saveTheme).not.toHaveBeenCalled();
    expect(onThemeChange).toHaveBeenCalledWith("light");
  });

  it("should select the System tile when auto theme is enabled", () => {
    themeInit.isAutoThemeEnabled.mockReturnValue(true);
    const { container } = render(<ThemeSwitcher />);
    // The selected tile must be System specifically, not just any tile: locate
    // the System tile by its title and assert its segmented item is selected.
    const systemTile = container.querySelector('[title="System"]');
    expect(systemTile).toBeInTheDocument();
    expect(systemTile?.closest(".ant-segmented-item")).toHaveClass(
      "ant-segmented-item-selected",
    );
  });
});

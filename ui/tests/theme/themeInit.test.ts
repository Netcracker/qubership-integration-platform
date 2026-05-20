/**
 * @jest-environment jsdom
 */
/* eslint-disable @typescript-eslint/unbound-method -- jest mock assertions */
import {
  getSystemTheme,
  getSavedTheme,
  saveTheme,
  clearSavedTheme,
  initializeBrowserTheme,
  setupThemeListener,
  resetToSystemTheme,
  enableAutoThemeSwitching,
  isAutoThemeEnabled,
  applyThemeToDOM,
} from "../../src/theme/themeInit";

const THEME_STORAGE_KEY = "qip-ui-theme-mode"; // must match themeInit.ts

describe("themeInit", () => {
  let originalLocalStorage: Storage;
  let originalMatchMedia: typeof window.matchMedia;
  let originalRequestAnimationFrame: typeof window.requestAnimationFrame;

  beforeEach(() => {
    originalLocalStorage = global.localStorage;
    originalMatchMedia = window.matchMedia;
    originalRequestAnimationFrame = window.requestAnimationFrame;
    jest.useFakeTimers();
    Object.defineProperty(global, "localStorage", {
      value: {
        getItem: jest.fn(),
        setItem: jest.fn(),
        removeItem: jest.fn(),
      },
      writable: true,
    });
    Object.defineProperty(window, "matchMedia", {
      value: jest.fn((_query: string) => ({
        matches: false,
        addEventListener: jest.fn(),
        removeEventListener: jest.fn(),
      })),
      writable: true,
    });
    Object.defineProperty(window, "requestAnimationFrame", {
      value: (cb: () => void) => setTimeout(cb, 0),
      writable: true,
    });
    document.documentElement.setAttribute("data-theme", "");
    document.body.className = "";
  });

  afterEach(() => {
    jest.useRealTimers();
    Object.defineProperty(global, "localStorage", {
      value: originalLocalStorage,
      writable: true,
    });
    Object.defineProperty(window, "matchMedia", {
      value: originalMatchMedia,
      writable: true,
    });
    Object.defineProperty(window, "requestAnimationFrame", {
      value: originalRequestAnimationFrame,
      writable: true,
    });
  });

  describe("getSavedTheme", () => {
    it("returns null when localStorage is empty", () => {
      (localStorage.getItem as jest.Mock).mockReturnValue(null);
      expect(getSavedTheme()).toBeNull();
    });

    it("returns theme when valid value is stored", () => {
      (localStorage.getItem as jest.Mock).mockReturnValue("dark");
      expect(getSavedTheme()).toBe("dark");
    });

    it("returns null for invalid stored value", () => {
      (localStorage.getItem as jest.Mock).mockReturnValue("invalid");
      expect(getSavedTheme()).toBeNull();
    });

    it("returns light when light is stored", () => {
      (localStorage.getItem as jest.Mock).mockReturnValue("light");
      expect(getSavedTheme()).toBe("light");
    });

    it("returns high-contrast when high-contrast is stored", () => {
      (localStorage.getItem as jest.Mock).mockReturnValue("high-contrast");
      expect(getSavedTheme()).toBe("high-contrast");
    });
  });

  describe("saveTheme", () => {
    it("saves theme to localStorage", () => {
      saveTheme("dark");
      expect(localStorage.setItem).toHaveBeenCalledWith(
        THEME_STORAGE_KEY,
        "dark",
      );
    });
  });

  describe("clearSavedTheme", () => {
    it("removes theme from localStorage", () => {
      clearSavedTheme();
      expect(localStorage.removeItem).toHaveBeenCalledWith(THEME_STORAGE_KEY);
    });
  });

  describe("getSystemTheme", () => {
    it("returns light when prefers-color-scheme is light", () => {
      (window.matchMedia as jest.Mock).mockImplementation(() => ({
        matches: false,
        addEventListener: jest.fn(),
        removeEventListener: jest.fn(),
      }));
      expect(getSystemTheme()).toBe("light");
    });

    it("returns dark when prefers-color-scheme is dark", () => {
      (window.matchMedia as jest.Mock).mockImplementation((query: string) => ({
        matches: query === "(prefers-color-scheme: dark)",
        addEventListener: jest.fn(),
        removeEventListener: jest.fn(),
      }));
      expect(getSystemTheme()).toBe("dark");
    });

    it("returns high-contrast when prefers-contrast is more", () => {
      (window.matchMedia as jest.Mock).mockImplementation((query: string) => ({
        matches: query === "(prefers-contrast: more)",
        addEventListener: jest.fn(),
        removeEventListener: jest.fn(),
      }));
      expect(getSystemTheme()).toBe("high-contrast");
    });
  });

  describe("initializeBrowserTheme", () => {
    it("uses saved theme when available", () => {
      (localStorage.getItem as jest.Mock).mockReturnValue("dark");
      const result = initializeBrowserTheme();
      expect(result).toBe("dark");
      expect(document.documentElement.getAttribute("data-theme")).toBe("dark");
      expect(document.body.classList.contains("theme-dark")).toBe(true);
    });

    it("falls back to system theme when no saved theme", () => {
      (localStorage.getItem as jest.Mock).mockReturnValue(null);
      (window.matchMedia as jest.Mock).mockImplementation((query: string) => ({
        matches: query === "(prefers-color-scheme: dark)",
        addEventListener: jest.fn(),
        removeEventListener: jest.fn(),
      }));
      const result = initializeBrowserTheme();
      expect(result).toBe("dark");
    });

    it("applies theme to DOM", () => {
      (localStorage.getItem as jest.Mock).mockReturnValue("light");
      initializeBrowserTheme();
      expect(document.documentElement.getAttribute("data-theme")).toBe("light");
      expect(document.body.classList.contains("theme-light")).toBe(true);
    });
  });

  describe("setupThemeListener", () => {
    it("does not call callback when theme is saved and system changes", () => {
      let changeHandler: (() => void) | null = null;
      (localStorage.getItem as jest.Mock).mockReturnValue("dark");
      (window.matchMedia as jest.Mock).mockImplementation((_q: string) => ({
        matches: false,
        addEventListener: (_ev: string, fn: () => void) => {
          changeHandler = fn;
        },
        removeEventListener: jest.fn(),
      }));
      const callback = jest.fn();
      setupThemeListener(callback);
      changeHandler!();
      expect(callback).not.toHaveBeenCalled();
    });

    it("syncs theme on storage event from another tab", () => {
      const callback = jest.fn();
      const unsubscribe = setupThemeListener(callback);

      const storageEvent = new StorageEvent("storage", {
        key: THEME_STORAGE_KEY,
        newValue: "dark",
      });
      window.dispatchEvent(storageEvent);

      expect(callback).toHaveBeenCalledWith("dark");
      expect(document.documentElement.getAttribute("data-theme")).toBe("dark");

      unsubscribe();
    });

    it("syncs to light theme on storage event", () => {
      const callback = jest.fn();
      setupThemeListener(callback);
      window.dispatchEvent(
        new StorageEvent("storage", {
          key: THEME_STORAGE_KEY,
          newValue: "light",
        }),
      );
      expect(callback).toHaveBeenCalledWith("light");
      expect(document.documentElement.getAttribute("data-theme")).toBe("light");
    });

    it("syncs to high-contrast theme on storage event", () => {
      const callback = jest.fn();
      setupThemeListener(callback);
      window.dispatchEvent(
        new StorageEvent("storage", {
          key: THEME_STORAGE_KEY,
          newValue: "high-contrast",
        }),
      );
      expect(callback).toHaveBeenCalledWith("high-contrast");
      expect(document.documentElement.getAttribute("data-theme")).toBe(
        "high-contrast",
      );
    });

    it("ignores storage event for other keys", () => {
      const callback = jest.fn();
      const unsubscribe = setupThemeListener(callback);

      const storageEvent = new StorageEvent("storage", {
        key: "other-key",
        newValue: "dark",
      });
      window.dispatchEvent(storageEvent);

      expect(callback).not.toHaveBeenCalled();
      unsubscribe();
    });

    it("ignores storage event with invalid theme value", () => {
      const callback = jest.fn();
      const unsubscribe = setupThemeListener(callback);

      const storageEvent = new StorageEvent("storage", {
        key: THEME_STORAGE_KEY,
        newValue: "invalid-theme",
      });
      window.dispatchEvent(storageEvent);

      expect(callback).not.toHaveBeenCalled();
      unsubscribe();
    });

    it("unsubscribes storage listener on cleanup", () => {
      const removeSpy = jest.spyOn(window, "removeEventListener");
      const unsubscribe = setupThemeListener(jest.fn());
      unsubscribe();
      expect(removeSpy).toHaveBeenCalledWith("storage", expect.any(Function));
    });
  });

  describe("resetToSystemTheme", () => {
    it("clears saved theme and applies system theme", () => {
      (localStorage.getItem as jest.Mock).mockReturnValue(null);
      (window.matchMedia as jest.Mock).mockImplementation((query: string) => ({
        matches: query === "(prefers-color-scheme: dark)",
        addEventListener: jest.fn(),
        removeEventListener: jest.fn(),
      }));
      const result = resetToSystemTheme();
      expect(localStorage.removeItem).toHaveBeenCalledWith(THEME_STORAGE_KEY);
      expect(result).toBe("dark");
    });
  });

  describe("enableAutoThemeSwitching", () => {
    it("clears saved theme and applies system theme", () => {
      enableAutoThemeSwitching();
      expect(localStorage.removeItem).toHaveBeenCalledWith(THEME_STORAGE_KEY);
    });
  });

  describe("isAutoThemeEnabled", () => {
    it("returns true when no theme is saved", () => {
      (localStorage.getItem as jest.Mock).mockReturnValue(null);
      expect(isAutoThemeEnabled()).toBe(true);
    });

    it("returns false when theme is saved", () => {
      (localStorage.getItem as jest.Mock).mockReturnValue("dark");
      expect(isAutoThemeEnabled()).toBe(false);
    });
  });

  describe("applyThemeToDOM", () => {
    it("sets data-theme attribute and body class", () => {
      applyThemeToDOM("high-contrast");
      expect(document.documentElement.getAttribute("data-theme")).toBe(
        "high-contrast",
      );
      expect(document.body.classList.contains("theme-high-contrast")).toBe(
        true,
      );
    });

    it("applies inline variables via requestAnimationFrame and dispatches event", () => {
      applyThemeToDOM("dark");
      jest.advanceTimersByTime(0);
      jest.advanceTimersByTime(0);
      jest.advanceTimersByTime(300);
      expect(
        document.documentElement.classList.contains("theme-switching"),
      ).toBe(false);
    });
  });
});

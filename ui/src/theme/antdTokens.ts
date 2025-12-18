import { theme as antdTheme, ThemeConfig } from 'antd';
import type { AliasToken } from 'antd/es/theme/internal';

type UnknownRecord = Record<string, unknown>;

function isPlainObject(value: unknown): value is UnknownRecord {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function mergeComponentConfig(baseValue: unknown, overrideValue: unknown): unknown {
  if (overrideValue === undefined) {
    return baseValue;
  }

  if (isPlainObject(baseValue) && isPlainObject(overrideValue)) {
    return { ...baseValue, ...overrideValue };
  }

  return overrideValue;
}

function getCSSVariable(name: string, fallback: string = ''): string {
  if (typeof window === 'undefined' || typeof document === 'undefined') {
    return fallback;
  }

  const value = getComputedStyle(document.documentElement).getPropertyValue(name).trim();
  return value || fallback;
}

export function getThemeTokens(isDark: boolean): Partial<AliasToken> {
  const editorBg = getCSSVariable('--vscode-editor-background', isDark ? '#141414' : '#ffffff');
  const panelBg = getCSSVariable('--vscode-panel-background', isDark ? '#1f1f1f' : '#fafafa');
  const foreground = getCSSVariable('--vscode-foreground', isDark ? 'rgba(255, 255, 255, 0.85)' : 'rgba(0, 0, 0, 0.88)');
  const border = getCSSVariable('--vscode-border', isDark ? '#303030' : '#d9d9d9');
  const buttonBg = getCSSVariable('--vscode-button-background', '#0078d4');
  const buttonHover = getCSSVariable('--vscode-button-hoverBackground', '#106ebe');
  const linkColor = getCSSVariable('--vscode-textLink-foreground', isDark ? '#69b1ff' : '#1677ff');
  const errorColor = getCSSVariable('--vscode-errorForeground', isDark ? '#f48771' : '#d73a49');
  const warningColor = getCSSVariable('--vscode-warningForeground', isDark ? '#ffcc02' : '#e36209');
  const inputBg = getCSSVariable('--vscode-input-background', isDark ? '#1f1f1f' : '#ffffff');
  return {
    colorBgBase: editorBg,
    colorBgContainer: inputBg,
    colorBgElevated: panelBg,
    colorBgLayout: panelBg,
    colorBgSpotlight: panelBg,

    colorTextBase: foreground,
    colorText: foreground,
    colorTextSecondary: getCSSVariable('--vscode-descriptionForeground', isDark ? '#9ca3af' : '#9ca3af'),

    colorBorder: border,
    colorBorderSecondary: border,

    colorPrimary: buttonBg,
    colorPrimaryHover: buttonHover,
    colorLink: linkColor,
    colorLinkHover: buttonHover,

    colorError: errorColor,
    colorWarning: warningColor,
    colorSuccess: getCSSVariable('--vscode-terminal-ansiGreen', isDark ? '#4ec9b0' : '#28a745'),
    colorInfo: linkColor,

    colorBgTextHover: getCSSVariable('--vscode-list-hoverBackground', isDark ? '#262626' : '#f5f5f5'),

    fontFamily: getCSSVariable('--vscode-font-family', 'Segoe UI, Tahoma, Geneva, Verdana, sans-serif'),
    fontSize: 13,
    lineHeight: 1.5,

    borderRadius: 4,
    controlHeight: 32,
  };
}

export function deepMergeThemeConfig(
  base: ThemeConfig,
  overrides: Partial<ThemeConfig>
): ThemeConfig {
  const merged: ThemeConfig = {
    ...base,
    ...overrides,
  };

  if (overrides.token) {
    merged.token = {
      ...(base.token ?? {}),
      ...overrides.token,
    } as ThemeConfig["token"];
  }

  if (overrides.components) {
    const baseComponents = (base.components ?? {}) as UnknownRecord;
    const overrideComponents = overrides.components as unknown as UnknownRecord;
    const mergedComponents: UnknownRecord = { ...baseComponents };

    for (const [componentName, overrideValue] of Object.entries(overrideComponents)) {
      mergedComponents[componentName] = mergeComponentConfig(
        baseComponents[componentName],
        overrideValue,
      );
    }

    merged.components = mergedComponents as ThemeConfig["components"];
  }

  return merged;
}

export function getAntdThemeConfig(isDark: boolean, themeOverrides?: Partial<ThemeConfig>): ThemeConfig {
  const tokens = getThemeTokens(isDark);
  const modalBg = getCSSVariable('--vscode-modal-background', tokens.colorBgElevated ?? tokens.colorBgBase ?? (isDark ? '#1f1f1f' : '#fafafa'));
  const modalForeground = getCSSVariable('--vscode-modal-foreground', tokens.colorText ?? (isDark ? 'rgba(255, 255, 255, 0.85)' : 'rgba(0, 0, 0, 0.88)'));
  const borderColor = getCSSVariable('--vscode-border', isDark ? '#303030' : '#d9d9d9');
  const iconHover = getCSSVariable('--vscode-button-hoverBackground', '#106ebe');

  const baseConfig: ThemeConfig = {
    algorithm: isDark ? antdTheme.darkAlgorithm : antdTheme.defaultAlgorithm,
    token: tokens,
    components: {
      Layout: {
        headerBg: getCSSVariable('--vscode-editor-background', isDark ? '#141414' : '#ffffff'),
        bodyBg: getCSSVariable('--vscode-editor-background', isDark ? '#141414' : '#ffffff'),
        siderBg: getCSSVariable('--vscode-sideBar-background', isDark ? '#141414' : '#ffffff'),
      },
      Menu: {
        itemBg: 'transparent',
        itemSelectedBg: getCSSVariable('--vscode-list-activeSelectionBackground', '#0078d4'),
        itemSelectedColor: getCSSVariable('--vscode-list-activeSelectionForeground', '#ffffff'),
        itemHoverBg: getCSSVariable('--vscode-list-hoverBackground', isDark ? '#262626' : '#f5f5f5'),
      },
      Table: {
        headerBg: getCSSVariable('--vscode-editor-background', isDark ? '#1f1f1f' : '#fafafa'),
        rowHoverBg: getCSSVariable('--vscode-list-hoverBackground', isDark ? '#262626' : '#f5f5f5'),
        colorBorder: getCSSVariable('--vscode-editorGroup-border', isDark ? '#303030' : '#d9d9d9'),
        colorBorderSecondary: getCSSVariable('--vscode-border', isDark ? '#303030' : '#d9d9d9'),
        colorBgContainer: getCSSVariable('--vscode-editor-background', isDark ? '#141414' : '#ffffff'),
        colorText: getCSSVariable('--vscode-foreground', isDark ? 'rgba(255, 255, 255, 0.85)' : 'rgba(0, 0, 0, 0.88)'),
        colorTextHeading: getCSSVariable('--vscode-foreground', isDark ? 'rgba(255, 255, 255, 0.85)' : 'rgba(0, 0, 0, 0.88)'),
      },
      Button: {
        colorPrimary: getCSSVariable('--vscode-button-background', '#0078d4'),
        colorPrimaryHover: getCSSVariable('--vscode-button-hoverBackground', '#106ebe'),
        colorText: getCSSVariable('--vscode-foreground', isDark ? 'rgba(255, 255, 255, 0.85)' : 'rgba(0, 0, 0, 0.88)'),
        colorBgContainer: getCSSVariable('--vscode-editor-background', isDark ? '#141414' : '#ffffff'),
        defaultBorderColor: getCSSVariable('--vscode-editorGroup-border', isDark ? '#303030' : '#d9d9d9'),
        defaultColor: getCSSVariable('--vscode-foreground', isDark ? 'rgba(255, 255, 255, 0.85)' : 'rgba(0, 0, 0, 0.88)'),
      },
      Input: {
        colorBgContainer: getCSSVariable('--vscode-input-background', isDark ? '#1f1f1f' : '#ffffff'),
        activeBg: getCSSVariable('--vscode-input-background', isDark ? '#1f1f1f' : '#ffffff'),
        colorText: getCSSVariable('--vscode-input-foreground', isDark ? 'rgba(255, 255, 255, 0.85)' : 'rgba(0, 0, 0, 0.88)'),
        colorBorder: getCSSVariable('--vscode-input-border', isDark ? '#303030' : '#d9d9d9'),
        colorTextPlaceholder: getCSSVariable('--vscode-input-placeholderForeground', isDark ? 'rgba(255, 255, 255, 0.45)' : 'rgba(0, 0, 0, 0.45)'),
        hoverBg: getCSSVariable('--vscode-input-background', isDark ? '#1f1f1f' : '#ffffff'),
      },
      Drawer: {
        colorBgElevated: getCSSVariable('--vscode-editor-background', isDark ? '#141414' : '#ffffff'),
        colorText: getCSSVariable('--vscode-foreground', isDark ? 'rgba(255, 255, 255, 0.85)' : 'rgba(0, 0, 0, 0.88)'),
        colorBorder: getCSSVariable('--vscode-border', isDark ? '#303030' : '#d9d9d9'),
      },
      List: {
        colorBgContainer: getCSSVariable('--vscode-editor-background', isDark ? '#141414' : '#ffffff'),
        colorText: getCSSVariable('--vscode-foreground', isDark ? 'rgba(255, 255, 255, 0.85)' : 'rgba(0, 0, 0, 0.88)'),
        colorTextDescription: getCSSVariable('--vscode-descriptionForeground', isDark ? '#9ca3af' : '#9ca3af'),
      },
      Form: {
        labelColor: getCSSVariable('--vscode-foreground', isDark ? 'rgba(255, 255, 255, 0.85)' : 'rgba(0, 0, 0, 0.88)'),
      },
      Tooltip: {
        colorBgSpotlight: getCSSVariable('--vscode-panel-background', isDark ? '#1f1f1f' : '#fafafa'),
        colorTextLightSolid: getCSSVariable('--vscode-foreground', isDark ? 'rgba(255, 255, 255, 0.85)' : 'rgba(0, 0, 0, 0.88)'),
        colorBorder: getCSSVariable('--vscode-border', isDark ? '#303030' : '#d9d9d9'),
      },
      FloatButton: {
        colorBgElevated: getCSSVariable('--vscode-panel-background', isDark ? '#1f1f1f' : '#fafafa'),
        colorText: getCSSVariable('--vscode-foreground', isDark ? 'rgba(255, 255, 255, 0.85)' : 'rgba(0, 0, 0, 0.88)'),
        colorBorder: getCSSVariable('--vscode-border', isDark ? '#303030' : '#d9d9d9'),
        colorPrimary: getCSSVariable('--vscode-button-background', '#0078d4'),
        colorPrimaryHover: getCSSVariable('--vscode-button-hoverBackground', '#106ebe'),
      },
      Modal: {
        colorBgElevated: modalBg,
        colorBgBase: modalBg,
        headerBg: modalBg,
        titleColor: modalForeground,
        colorText: modalForeground,
        colorTextHeading: modalForeground,
        colorIcon: modalForeground,
        colorIconHover: iconHover,
        colorBorder: borderColor,
        borderRadiusLG: 8,
        padding: 16,
      },
      Segmented: {
        itemColor: getCSSVariable('--vscode-foreground', isDark ? 'rgba(255, 255, 255, 0.85)' : 'rgba(0, 0, 0, 0.88)'),
        itemSelectedBg: getCSSVariable('--vscode-button-background', '#0078d4'),
        itemSelectedColor: getCSSVariable('--vscode-button-foreground', '#ffffff'),
        itemHoverBg: getCSSVariable('--vscode-list-hoverBackground', isDark ? '#262626' : '#f5f5f5'),
        itemHoverColor: getCSSVariable('--vscode-foreground', isDark ? 'rgba(255, 255, 255, 0.85)' : 'rgba(0, 0, 0, 0.88)'),
        trackBg: getCSSVariable('--vscode-editor-background', isDark ? '#141414' : '#ffffff'),
        trackPadding: 2,
        borderRadius: 4,
      },
      Tabs: {
        itemColor: getCSSVariable('--vscode-foreground', isDark ? 'rgba(255, 255, 255, 0.85)' : 'rgba(0, 0, 0, 0.88)'),
        itemSelectedColor: getCSSVariable('--vscode-textLink-foreground', isDark ? '#69b1ff' : '#1677ff'),
        itemHoverColor: getCSSVariable('--vscode-textLink-foreground', isDark ? '#69b1ff' : '#1677ff'),
        itemActiveColor: getCSSVariable('--vscode-textLink-foreground', isDark ? '#69b1ff' : '#1677ff'),
        inkBarColor: getCSSVariable('--vscode-editorGroup-border', isDark ? '#303030' : '#d9d9d9'),
        cardBg: getCSSVariable('--vscode-editor-background', isDark ? '#141414' : '#ffffff'),
        cardPadding: '12px 16px',
      },
    },
  };

  if (themeOverrides) {
    return deepMergeThemeConfig(baseConfig, themeOverrides);
  }

  return baseConfig;
}


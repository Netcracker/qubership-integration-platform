import React, {
  createContext,
  ReactNode,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
} from "react";
import Icon from "@ant-design/icons";
import type { AntdIconProps } from "@ant-design/icons/lib/components/AntdIcon";
import parse from "html-react-parser";
import { commonIcons, elementIcons } from "./IconDefenitions";
import { getConfig, onConfigChange } from "../appConfig.ts";
import {
  isMonochromeSvgElement,
  isMonochromeSvgString,
  normalizeSvgElementPaintToCurrentColor,
  normalizeSvgStringPaintToCurrentColor,
} from "./svgColorNormalization.ts";
import { IconWithDomColorNormalize } from "./IconWithDomColorNormalize.tsx";

export type IconSource =
  | React.ComponentType<AntdIconProps>
  | string
  | React.ReactElement;

const allIcons = {
  ...commonIcons,
  ...elementIcons,
};

/** Слияние конфига: устаревший ключ `icons.qip` подставляет `logo`, если `logo` не задан. */
function mergeAllIconsWithConfig(
  configIcons: IconOverrides | undefined,
): IconOverrides {
  if (!configIcons) {
    return allIcons;
  }
  const merged: IconOverrides = { ...allIcons, ...configIcons };
  const hasOwn = (key: string) =>
    Object.prototype.hasOwnProperty.call(configIcons, key);
  if (hasOwn("qip") && configIcons.qip !== undefined && !hasOwn("logo")) {
    merged.logo = configIcons.qip;
  }
  return merged;
}

export interface IconContextType {
  icons: IconOverrides;
  setIcons: (icons: IconOverrides) => void;
}

export const IconContext = createContext<IconContextType>({
  icons: allIcons,
  setIcons: () => {},
});

export const IconProvider: React.FC<{ children: ReactNode }> = ({
  children,
}) => {
  const [icons, setIcons] = useState<IconOverrides>(() =>
    mergeAllIconsWithConfig(getConfig().icons),
  );

  useEffect(() => {
    const applyConfig = (cfg: ReturnType<typeof getConfig>) => {
      setIcons(() => mergeAllIconsWithConfig(cfg.icons));
    };

    applyConfig(getConfig());
    return onConfigChange((cfg) => applyConfig(cfg));
  }, []);

  const mergeIcons = useCallback((overrides: IconOverrides) => {
    setIcons((prev) => ({
      ...prev,
      ...overrides,
    }));
  }, []);

  const contextValue = useMemo(
    () => ({ icons, setIcons: mergeIcons }),
    [icons, mergeIcons],
  );

  return (
    <IconContext.Provider value={contextValue}>{children}</IconContext.Provider>
  );
};

export const useIcons = () => {
  const context = useContext(IconContext);
  if (!context) {
    throw new Error("useIcons must be used within IconProvider");
  }
  return context;
};

interface OverridableIconProps extends Omit<AntdIconProps, "name"> {
  name: IconName;
}

export type IconName = keyof typeof allIcons | (string & {});

export type IconOverrides = {
  [K in IconName]?: IconSource;
};

type RenderedSource =
  | { kind: "element"; render: () => React.ReactElement }
  | { kind: "string"; render: () => React.ReactElement }
  | { kind: "component"; component: React.ComponentType<AntdIconProps> }
  | null;

const sourceCache = new WeakMap<object, RenderedSource>();
const stringSourceCache = new Map<string, RenderedSource>();

const buildSource = (
  IconComponent: IconSource,
  name: string,
): RenderedSource => {
  if (React.isValidElement(IconComponent)) {
    const normalized = isMonochromeSvgElement(IconComponent)
      ? normalizeSvgElementPaintToCurrentColor(IconComponent)
      : IconComponent;
    const wrappedNode = React.cloneElement(normalized, {
      width: "1em",
      height: "1em",
    });
    const render = () => wrappedNode;
    return { kind: "element", render };
  }

  if (typeof IconComponent === "string") {
    const normalizedSvg = isMonochromeSvgString(IconComponent)
      ? normalizeSvgStringPaintToCurrentColor(IconComponent)
      : IconComponent;
    const parsed = parse(normalizedSvg);
    const svgElement = Array.isArray(parsed)
      ? parsed.find((el) => React.isValidElement(el))
      : parsed;
    if (!React.isValidElement(svgElement)) {
      console.warn(
        `[IconProvider] Failed to parse icon "${name}". Parse result:`,
        parsed,
      );
      return null;
    }
    const sizedSvg = React.cloneElement(
      svgElement as React.ReactElement<React.SVGProps<SVGSVGElement>>,
      { width: "1em", height: "1em" },
    );
    const render = () => sizedSvg;
    return { kind: "string", render };
  }

  return {
    kind: "component",
    component: IconComponent as React.ComponentType<AntdIconProps>,
  };
};

const getCachedSource = (
  IconComponent: IconSource,
  name: string,
): RenderedSource => {
  if (typeof IconComponent === "string") {
    let cached = stringSourceCache.get(IconComponent);
    if (cached === undefined) {
      cached = buildSource(IconComponent, name);
      stringSourceCache.set(IconComponent, cached);
    }
    return cached;
  }
  if (
    typeof IconComponent === "object" ||
    typeof IconComponent === "function"
  ) {
    let cached = sourceCache.get(IconComponent as object);
    if (cached === undefined) {
      cached = buildSource(IconComponent, name);
      sourceCache.set(IconComponent as object, cached);
    }
    return cached;
  }
  return buildSource(IconComponent, name);
};

const OverridableIconImpl: React.FC<OverridableIconProps> = ({
  name,
  ...props
}) => {
  const icons = useIcons();
  const IconComponent = icons.icons[name];
  if (!IconComponent) {
    return null;
  }

  const source = getCachedSource(IconComponent, name);
  if (!source) return null;

  if (source.kind === "component") {
    return (
      <IconWithDomColorNormalize
        IconComponent={source.component}
        props={props}
      />
    );
  }

  return <Icon {...props} component={source.render} />;
};

export const OverridableIcon = React.memo(OverridableIconImpl);
OverridableIcon.displayName = "OverridableIcon";

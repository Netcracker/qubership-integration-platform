import React, {
  createContext,
  ReactNode,
  useContext,
  useEffect,
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
  const [icons, setIconsState] = useState<IconOverrides>(() => {
    const config = getConfig();
    return config.icons ? { ...allIcons, ...config.icons } : allIcons;
  });

  useEffect(() => {
    const applyConfig = (cfg: ReturnType<typeof getConfig>) => {
      if (cfg.icons) {
        setIconsState(() => ({ ...allIcons, ...cfg.icons }));
      } else {
        setIconsState(() => allIcons);
      }
    };

    applyConfig(getConfig());
    return onConfigChange((cfg) => applyConfig(cfg));
  }, []);

  const setIcons = (overrides: IconOverrides) => {
    setIconsState((prev) => ({
      ...prev,
      ...overrides,
    }));
  };

  return (
    <IconContext.Provider value={{ icons, setIcons }}>
      {children}
    </IconContext.Provider>
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

export const OverridableIcon: React.FC<OverridableIconProps> = ({
  name,
  ...props
}) => {
  const icons = useIcons();
  const IconComponent = icons.icons[name];
  if (!IconComponent) {
    return null;
  }

  // ReactElement (e.g. <svg>...</svg> passed directly)
  if (React.isValidElement(IconComponent)) {
    const normalized = isMonochromeSvgElement(IconComponent)
      ? normalizeSvgElementPaintToCurrentColor(IconComponent)
      : IconComponent;
    const wrappedNode = React.cloneElement(normalized, {
      width: "1em",
      height: "1em",
    });
    return <Icon {...props} component={() => wrappedNode} />;
  }

  // SVG string (e.g. "<svg>...</svg>" from config)
  if (typeof IconComponent === "string") {
    const normalizedSvg = isMonochromeSvgString(IconComponent)
      ? normalizeSvgStringPaintToCurrentColor(IconComponent)
      : IconComponent;
    const parsed = parse(normalizedSvg);
    // Handle case where parse returns an array (text nodes + SVG element)
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
    return <Icon {...props} component={() => sizedSvg} />;
  }

  // Component (function or class) — use DOM-based color normalization
  return (
    <IconWithDomColorNormalize
      IconComponent={IconComponent as React.ComponentType<AntdIconProps>}
      props={props}
    />
  );
};

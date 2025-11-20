import React, { createContext, useContext, ReactNode, useState } from "react";
import Icon from "@ant-design/icons";
import type { AntdIconProps } from "@ant-design/icons/lib/components/AntdIcon";
import parse from "html-react-parser";
import { commonIcons, elementIcons } from "./IconDefenitions";

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
  const [icons, setIconsState] = useState<IconOverrides>(allIcons);

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

export type IconName = keyof typeof allIcons;

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
    console.warn(`Icon "${name}" not found in IconProvider`);
    return null;
  }

  if (React.isValidElement(IconComponent)) {
    return React.cloneElement(IconComponent, props);
  }

  if (typeof IconComponent === "string") {
    const parsed = parse(IconComponent);
    if (!React.isValidElement(parsed)) {
      console.warn("Parsed icon is not a React element:", parsed);
      return null;
    }
    const sizedSvg = React.cloneElement(parsed, {
      width: "1em",
      height: "1em",
      ...props,
    });
    return <Icon component={() => sizedSvg} />;
  }

  // @ts-expect-error all cases covered
  return <IconComponent {...props} />;
};

import React, {
  createContext,
  ReactNode,
  useContext,
  useEffect,
  useLayoutEffect,
  useRef,
  useState,
} from "react";
import Icon from "@ant-design/icons";
import type { AntdIconProps } from "@ant-design/icons/lib/components/AntdIcon";
import parse from "html-react-parser";
import { commonIcons, elementIcons } from "./IconDefenitions";
import { getConfig, onConfigChange } from "../appConfig.ts";

export type IconSource =
  | React.ComponentType<AntdIconProps>
  | string
  | React.ReactElement;

const allIcons = {
  ...commonIcons,
  ...elementIcons,
};

const SVG_FILL_STROKE_SELECTOR =
  "path, circle, rect, ellipse, polygon, line, polyline";
const OVERRIDE_ICON_FONT_SIZE = "1.15em";

function applyOverrideIconSize(
  props: AntdIconProps,
  isOverride: boolean,
): AntdIconProps {
  if (!isOverride) return props;
  const style: React.CSSProperties = props.style ?? {};
  if (style.fontSize) return props;
  return {
    ...props,
    style: {
      ...style,
      fontSize: OVERRIDE_ICON_FONT_SIZE,
    },
  };
}

function normalizePaintToken(value: string): string {
  return value.trim().toLowerCase().replace(/\s+/g, " ");
}

function isIgnorablePaint(value: string): boolean {
  const v = normalizePaintToken(value);
  return (
    v === "" ||
    v === "none" ||
    v === "transparent" ||
    v === "currentcolor" ||
    v === "inherit" ||
    v === "initial" ||
    v === "unset" ||
    v === "context-fill" ||
    v === "context-stroke" ||
    v.startsWith("url(")
  );
}

function isLiteralColorToken(value: string): boolean {
  const v = normalizePaintToken(value);
  if (isIgnorablePaint(v)) return false;
  if (v.startsWith("var(")) return false;
  if (/^#[0-9a-f]{3,8}$/i.test(v)) return true;
  if (/^(rgb|rgba|hsl|hsla)\(/i.test(v)) return true;
  // Minimal named-color support for common cases (we keep this conservative on purpose).
  if (
    v === "black" ||
    v === "white" ||
    v === "gray" ||
    v === "grey" ||
    v === "red" ||
    v === "green" ||
    v === "blue"
  ) {
    return true;
  }
  return false;
}

function collectLiteralPaintsFromReactNode(
  node: ReactNode,
  paints: Set<string>,
) {
  if (!React.isValidElement(node)) return;
  const props = node.props as Record<string, unknown>;

  const fill = props.fill;
  const stroke = props.stroke;
  if (typeof fill === "string" && isLiteralColorToken(fill)) {
    paints.add(normalizePaintToken(fill));
  }
  if (typeof stroke === "string" && isLiteralColorToken(stroke)) {
    paints.add(normalizePaintToken(stroke));
  }

  const style = props.style;
  if (style && typeof style === "object" && !Array.isArray(style)) {
    const styleObj = style as Record<string, unknown>;
    if (
      typeof styleObj.fill === "string" &&
      isLiteralColorToken(styleObj.fill)
    ) {
      paints.add(normalizePaintToken(styleObj.fill));
    }
    if (
      typeof styleObj.stroke === "string" &&
      isLiteralColorToken(styleObj.stroke)
    ) {
      paints.add(normalizePaintToken(styleObj.stroke));
    }
  }

  if (props.children) {
    React.Children.forEach(props.children as ReactNode, (child) =>
      collectLiteralPaintsFromReactNode(child, paints),
    );
  }
}

/** Decide whether a React SVG tree is monochrome (0-1 literal fill/stroke colors). Conservative by design. */
function isMonochromeSvgElement(node: React.ReactElement): boolean {
  const paints = new Set<string>();
  collectLiteralPaintsFromReactNode(node, paints);
  return paints.size <= 1;
}

/** Replace literal fill/stroke colors with currentColor in props/style for a React SVG tree. */
function normalizeSvgElementPaintToCurrentColor(
  node: React.ReactElement,
): React.ReactElement {
  const props = node.props as Record<string, unknown>;
  const nextProps: Record<string, unknown> = { ...props };

  if (
    typeof nextProps.fill === "string" &&
    isLiteralColorToken(nextProps.fill)
  ) {
    nextProps.fill = "currentColor";
  }
  if (
    typeof nextProps.stroke === "string" &&
    isLiteralColorToken(nextProps.stroke)
  ) {
    nextProps.stroke = "currentColor";
  }

  if (
    nextProps.style &&
    typeof nextProps.style === "object" &&
    !Array.isArray(nextProps.style)
  ) {
    const style = { ...(nextProps.style as Record<string, unknown>) };
    if (typeof style.fill === "string" && isLiteralColorToken(style.fill)) {
      style.fill = "currentColor";
    }
    if (typeof style.stroke === "string" && isLiteralColorToken(style.stroke)) {
      style.stroke = "currentColor";
    }
    nextProps.style = style;
  }

  let normalizedChildren: ReactNode = props.children as ReactNode;
  if (props.children) {
    normalizedChildren = React.Children.map(
      props.children as ReactNode,
      (child) =>
        React.isValidElement(child)
          ? normalizeSvgElementPaintToCurrentColor(child)
          : child,
    ) as ReactNode;
  }

  return React.cloneElement(node, nextProps, normalizedChildren);
}

function safeSerializeSvgDocument(doc: Document): string | null {
  const svg = doc.documentElement;
  if (!svg || svg.tagName.toLowerCase() !== "svg") return null;
  // XMLSerializer exists in browsers/webviews.
  if (typeof XMLSerializer === "undefined") return null;
  return new XMLSerializer().serializeToString(svg);
}

function parseSvgToDocument(svgString: string): Document | null {
  if (typeof DOMParser === "undefined") return null;
  try {
    // image/svg+xml keeps attribute values intact.
    return new DOMParser().parseFromString(svgString, "image/svg+xml");
  } catch {
    return null;
  }
}

function isSvgDocColoredOrUnknown(doc: Document): boolean {
  const svg = doc.documentElement;
  if (!svg || svg.tagName.toLowerCase() !== "svg") return true;
  // If SVG contains gradients/patterns/images, treat as colored/complex.
  if (
    svg.querySelector(
      "linearGradient, radialGradient, pattern, image, mask, filter",
    )
  ) {
    return true;
  }
  // Style blocks can encode multiple colors; stay conservative.
  if (svg.querySelector("style")) return true;
  return false;
}

function collectLiteralPaintsFromSvgDoc(doc: Document): Set<string> {
  const svg = doc.documentElement;
  const paints = new Set<string>();
  if (!svg) return paints;

  const nodes = svg.querySelectorAll(SVG_FILL_STROKE_SELECTOR);
  nodes.forEach((node) => {
    const el = node as SVGElement;

    const fill = el.getAttribute("fill");
    if (fill && isLiteralColorToken(fill))
      paints.add(normalizePaintToken(fill));

    const stroke = el.getAttribute("stroke");
    if (stroke && isLiteralColorToken(stroke))
      paints.add(normalizePaintToken(stroke));

    const style = el.getAttribute("style");
    if (style) {
      const mFill = style.match(/fill\s*:\s*([^;]+)/i);
      const mStroke = style.match(/stroke\s*:\s*([^;]+)/i);
      if (mFill?.[1] && isLiteralColorToken(mFill[1]))
        paints.add(normalizePaintToken(mFill[1]));
      if (mStroke?.[1] && isLiteralColorToken(mStroke[1]))
        paints.add(normalizePaintToken(mStroke[1]));
    }
  });

  return paints;
}

function isMonochromeSvgString(svgString: string): boolean {
  const doc = parseSvgToDocument(svgString);
  if (!doc) return false; // unknown → do not touch
  if (isSvgDocColoredOrUnknown(doc)) return false;
  const paints = collectLiteralPaintsFromSvgDoc(doc);
  return paints.size <= 1;
}

function normalizeSvgStringPaintToCurrentColor(svgString: string): string {
  const doc = parseSvgToDocument(svgString);
  if (!doc) return svgString;
  if (isSvgDocColoredOrUnknown(doc)) return svgString;

  const paints = collectLiteralPaintsFromSvgDoc(doc);
  if (paints.size > 1) return svgString; // colored

  const svg = doc.documentElement;
  if (!svg) return svgString;

  const nodes = svg.querySelectorAll(SVG_FILL_STROKE_SELECTOR);
  nodes.forEach((node) => {
    const el = node as SVGElement;

    const fill = el.getAttribute("fill");
    if (fill && isLiteralColorToken(fill)) {
      el.setAttribute("fill", "currentColor");
    }

    const stroke = el.getAttribute("stroke");
    if (stroke && isLiteralColorToken(stroke)) {
      el.setAttribute("stroke", "currentColor");
    }

    const style = el.getAttribute("style");
    if (style) {
      const nextStyle = style
        .replace(/fill\s*:\s*([^;]+)/gi, (_m, v) =>
          isLiteralColorToken(String(v)) ? "fill: currentColor" : `fill: ${v}`,
        )
        .replace(/stroke\s*:\s*([^;]+)/gi, (_m, v) =>
          isLiteralColorToken(String(v))
            ? "stroke: currentColor"
            : `stroke: ${v}`,
        );
      el.setAttribute("style", nextStyle);
    }
  });

  return safeSerializeSvgDocument(doc) ?? svgString;
}

function IconWithDomColorNormalize({
  IconComponent,
  props,
}: {
  IconComponent: React.ComponentType<AntdIconProps>;
  props: AntdIconProps;
}) {
  const containerRef = useRef<HTMLSpanElement>(null);

  useLayoutEffect(() => {
    const el = containerRef.current;
    if (!el) return;

    // Ensure SVGR icons with hardcoded width/height behave like antd icons (1em).
    el.querySelectorAll("svg").forEach((svg) => {
      if (svg.getAttribute("width") && svg.getAttribute("width") !== "1em") {
        svg.setAttribute("width", "1em");
      }
      if (svg.getAttribute("height") && svg.getAttribute("height") !== "1em") {
        svg.setAttribute("height", "1em");
      }
    });

    const nodes = el.querySelectorAll(SVG_FILL_STROKE_SELECTOR);
    if (!nodes.length) return;

    // Detect monochrome based on computed styles (handles inline styles / <style> blocks).
    const paints = new Set<string>();
    nodes.forEach((node) => {
      const elem = node as SVGElement;
      const cs = getComputedStyle(elem);
      const fill = cs.fill ? normalizePaintToken(cs.fill) : "";
      const stroke = cs.stroke ? normalizePaintToken(cs.stroke) : "";
      if (fill && !isIgnorablePaint(fill)) paints.add(fill);
      if (stroke && !isIgnorablePaint(stroke)) paints.add(stroke);
    });

    // If there are multiple different paints, treat it as a colored icon → do not touch.
    if (paints.size > 1) return;

    // Monochrome: rewrite fill/stroke to currentColor where applicable.
    nodes.forEach((node) => {
      const elem = node as SVGElement;
      const cs = getComputedStyle(elem);

      const fill = cs.fill ? normalizePaintToken(cs.fill) : "";
      if (fill && !isIgnorablePaint(fill)) {
        elem.setAttribute("fill", "currentColor");
      }

      const stroke = cs.stroke ? normalizePaintToken(cs.stroke) : "";
      if (stroke && !isIgnorablePaint(stroke)) {
        elem.setAttribute("stroke", "currentColor");
      }
    });
  }, [IconComponent]);

  return (
    <Icon
      {...props}
      // Icon expects a component that renders an <svg/>; SVGR components fit.
      component={IconComponent as unknown as React.ComponentType<unknown>}
      ref={containerRef}
    />
  );
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
  const isOverride =
    (allIcons as Record<string, unknown>)[name] !== IconComponent;
  const nextProps = applyOverrideIconSize(props, isOverride);

  if (!IconComponent) {
    return null;
  }

  if (React.isValidElement(IconComponent)) {
    const normalized = isMonochromeSvgElement(IconComponent)
      ? normalizeSvgElementPaintToCurrentColor(IconComponent)
      : IconComponent;
    const wrappedNode = React.cloneElement(normalized, {
      width: "1em",
      height: "1em",
    });
    return <Icon {...nextProps} component={() => wrappedNode} />;
  }

  if (typeof IconComponent === "string") {
    const normalizedSvg = isMonochromeSvgString(IconComponent)
      ? normalizeSvgStringPaintToCurrentColor(IconComponent)
      : IconComponent;
    const parsed = parse(normalizedSvg);
    if (!React.isValidElement(parsed)) {
      console.warn("Parsed icon is not a React element:", parsed);
      return null;
    }
    const sizedSvg = React.cloneElement(
      parsed as React.ReactElement<React.SVGProps<SVGSVGElement>>,
      {
        width: "1em",
        height: "1em",
      },
    );
    return <Icon {...nextProps} component={() => sizedSvg} />;
  }

  return (
    <IconWithDomColorNormalize
      IconComponent={IconComponent as React.ComponentType<AntdIconProps>}
      props={nextProps}
    />
  );
};

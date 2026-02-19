import React, { ReactNode } from "react";

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

// ── React element tree normalization ──

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

export function isMonochromeSvgElement(node: React.ReactElement): boolean {
  const paints = new Set<string>();
  collectLiteralPaintsFromReactNode(node, paints);
  return paints.size <= 1;
}

export function normalizeSvgElementPaintToCurrentColor(
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

// ── SVG string normalization ──

function parseSvgToDocument(svgString: string): Document | null {
  if (typeof DOMParser === "undefined") return null;
  try {
    return new DOMParser().parseFromString(svgString, "image/svg+xml");
  } catch {
    return null;
  }
}

function safeSerializeSvgDocument(doc: Document): string | null {
  const svg = doc.documentElement;
  if (!svg || svg.tagName.toLowerCase() !== "svg") return null;
  if (typeof XMLSerializer === "undefined") return null;
  return new XMLSerializer().serializeToString(svg);
}

function isSvgDocColoredOrUnknown(doc: Document): boolean {
  const svg = doc.documentElement;
  if (!svg || svg.tagName.toLowerCase() !== "svg") return true;
  if (
    svg.querySelector(
      "linearGradient, radialGradient, pattern, image, mask, filter",
    )
  ) {
    return true;
  }
  if (svg.querySelector("style")) return true;
  return false;
}

const SVG_PAINT_SELECTOR =
  "path, circle, rect, ellipse, polygon, line, polyline";

function collectLiteralPaintsFromSvgDoc(doc: Document): Set<string> {
  const svg = doc.documentElement;
  const paints = new Set<string>();
  if (!svg) return paints;

  const nodes = svg.querySelectorAll(SVG_PAINT_SELECTOR);
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

export function isMonochromeSvgString(svgString: string): boolean {
  const doc = parseSvgToDocument(svgString);
  if (!doc) return false;
  if (isSvgDocColoredOrUnknown(doc)) return false;
  const paints = collectLiteralPaintsFromSvgDoc(doc);
  return paints.size <= 1;
}

export function normalizeSvgStringPaintToCurrentColor(
  svgString: string,
): string {
  const doc = parseSvgToDocument(svgString);
  if (!doc) return svgString;
  if (isSvgDocColoredOrUnknown(doc)) return svgString;

  const paints = collectLiteralPaintsFromSvgDoc(doc);
  if (paints.size > 1) return svgString;

  const svg = doc.documentElement;
  if (!svg) return svgString;

  const nodes = svg.querySelectorAll(SVG_PAINT_SELECTOR);
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

// ── DOM-based normalization (for rendered components) ──

export const SVG_FILL_STROKE_SELECTOR =
  "path, circle, rect, ellipse, polygon, line, polyline";

export { isIgnorablePaint, normalizePaintToken, isLiteralColorToken };

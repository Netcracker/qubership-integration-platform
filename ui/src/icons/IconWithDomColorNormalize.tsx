import React, { useLayoutEffect, useRef } from "react";
import Icon from "@ant-design/icons";
import type { AntdIconProps } from "@ant-design/icons/lib/components/AntdIcon";
import {
  SVG_FILL_STROKE_SELECTOR,
  isIgnorablePaint,
  normalizePaintToken,
} from "./svgColorNormalization.ts";

/**
 * Wraps any icon component and normalizes monochrome SVG fill/stroke
 * to `currentColor` via post-render DOM mutation.
 * This is the universal fallback for components that can't be
 * pre-processed (SVGR functions, class components, etc.).
 */

type PaintAttr = "fill" | "stroke";

type IconAnalysis =
  | { isMonochrome: false }
  | { isMonochrome: true; attrsByIndex: PaintAttr[][] };

const analysisCache = new WeakMap<
  React.ComponentType<AntdIconProps>,
  IconAnalysis
>();

const analyze = (nodes: NodeListOf<Element>): IconAnalysis => {
  const paints = new Set<string>();
  const attrsByIndex: PaintAttr[][] = [];
  nodes.forEach((node, idx) => {
    const cs = getComputedStyle(node as SVGElement);
    const fill = cs.fill ? normalizePaintToken(cs.fill) : "";
    const stroke = cs.stroke ? normalizePaintToken(cs.stroke) : "";
    const attrs: PaintAttr[] = [];
    if (fill && !isIgnorablePaint(fill)) {
      paints.add(fill);
      attrs.push("fill");
    }
    if (stroke && !isIgnorablePaint(stroke)) {
      paints.add(stroke);
      attrs.push("stroke");
    }
    attrsByIndex[idx] = attrs;
  });
  if (paints.size > 1) {
    return { isMonochrome: false };
  }
  return { isMonochrome: true, attrsByIndex };
};

const applyAttrs = (
  nodes: NodeListOf<Element>,
  attrsByIndex: PaintAttr[][],
) => {
  nodes.forEach((node, idx) => {
    const attrs = attrsByIndex[idx];
    if (!attrs?.length) return;
    if (attrs.includes("fill")) node.setAttribute("fill", "currentColor");
    if (attrs.includes("stroke")) node.setAttribute("stroke", "currentColor");
  });
};

type IconWithDomColorNormalizeProps = {
  IconComponent: React.ComponentType<AntdIconProps>;
  props: AntdIconProps;
};

const IconWithDomColorNormalizeImpl: React.FC<
  IconWithDomColorNormalizeProps
> = ({ IconComponent, props }) => {
  const containerRef = useRef<HTMLSpanElement>(null);

  useLayoutEffect(() => {
    const el = containerRef.current;
    if (!el) return;

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

    let analysis = analysisCache.get(IconComponent);
    if (!analysis) {
      analysis = analyze(nodes);
      analysisCache.set(IconComponent, analysis);
    }
    if (analysis.isMonochrome) {
      applyAttrs(nodes, analysis.attrsByIndex);
    }
  }, [IconComponent]);

  return (
    <Icon
      {...props}
      component={IconComponent as unknown as React.ComponentType<unknown>}
      ref={containerRef}
    />
  );
};

const shallowEqualProps = (a: AntdIconProps, b: AntdIconProps): boolean => {
  if (a === b) return true;
  const aKeys = Object.keys(a);
  const bKeys = Object.keys(b);
  if (aKeys.length !== bKeys.length) return false;
  for (const k of aKeys) {
    if (
      (a as Record<string, unknown>)[k] !== (b as Record<string, unknown>)[k]
    ) {
      return false;
    }
  }
  return true;
};

export const IconWithDomColorNormalize = React.memo(
  IconWithDomColorNormalizeImpl,
  (prev, next) =>
    prev.IconComponent === next.IconComponent &&
    shallowEqualProps(prev.props, next.props),
);
IconWithDomColorNormalize.displayName = "IconWithDomColorNormalize";

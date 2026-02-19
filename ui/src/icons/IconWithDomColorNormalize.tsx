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
export function IconWithDomColorNormalize({
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
      component={IconComponent as unknown as React.ComponentType<unknown>}
      ref={containerRef}
    />
  );
}

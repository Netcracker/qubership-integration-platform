import { useEffect, useState, useRef, MutableRefObject } from "react";

export function useResizeHeight<T extends HTMLElement>(): [
  MutableRefObject<T | null>,
  number,
] {
  const ref = useRef<T | null>(null);
  const [height, setHeight] = useState(0);

  useEffect(() => {
    const element = ref.current;
    if (!element) return;

    const update = () => setHeight(element.clientHeight);

    const ro = new ResizeObserver(update);
    ro.observe(element);
    update();

    return () => ro.disconnect();
  }, []);

  return [ref, height];
}

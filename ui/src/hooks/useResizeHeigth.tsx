import { useEffect, useState, useRef, MutableRefObject } from "react";

export function useResizeHeight<T extends HTMLElement>(): [
    MutableRefObject<T | null>,
    number
] {
    const ref = useRef<T | null>(null);
    const [height, setHeight] = useState(0);

    useEffect(() => {
        if (!ref.current) return;

        const update = () => setHeight(ref.current!.clientHeight);

        const ro = new ResizeObserver(update);
        ro.observe(ref.current);
        update();

        return () => ro.disconnect();
    }, []);

    return [ref, height];
}

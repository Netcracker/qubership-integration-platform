import React, { PropsWithChildren, useEffect, useRef, useState } from "react";

type AutoHeightProps = PropsWithChildren & React.HTMLAttributes<HTMLDivElement>;

export const AutoHeight: React.FC<AutoHeightProps> = ({
  children,
  ...props
}) => {
  const containerRef = useRef<HTMLDivElement>(null);
  const [height, setHeight] = useState(300);

  useEffect(() => {
    const el = containerRef.current;
    if (!el) return;

    const calcHeight = () => {
      const scrollParent = el.closest(".ant-modal-body") ?? el.parentElement;
      if (!scrollParent) return;
      const parentRect = scrollParent.getBoundingClientRect();
      const elRect = el.children[0].getBoundingClientRect();
      const available = parentRect.bottom - elRect.top - 60;
      if (available > 300) {
        setHeight(available);
      }
    };

    const modalWrap = el.closest(".ant-modal-wrap");
    if (modalWrap) {
      modalWrap.addEventListener("transitionend", calcHeight);
    }
    window.addEventListener("resize", calcHeight);

    return () => {
      modalWrap?.removeEventListener("transitionend", calcHeight);
      window.removeEventListener("resize", calcHeight);
    };
  }, []);

  return (
    <div ref={containerRef} {...props} style={{ ...props.style, height }}>
      {children}
    </div>
  );
};

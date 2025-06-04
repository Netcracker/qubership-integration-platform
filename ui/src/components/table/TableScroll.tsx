import React, { useEffect, useState, useRef } from 'react';
import type { TableProps } from 'antd';

interface TableScrollProps {
  children: React.ReactElement<TableProps<any>>;
  enableScroll?: boolean;
  className?: string;
}

export const TableScroll: React.FC<TableScrollProps> = ({ 
  children, 
  enableScroll = true,
  className = ''
}) => {
  const containerRef = useRef<HTMLDivElement>(null);
  const [scrollY, setScrollY] = useState<number>(0);

  useEffect(() => {
    if (enableScroll && containerRef.current) {
      const calculate = () => {
        const container = containerRef.current;
        if (container) {
          const tableElement = container.querySelector(".ant-table-wrapper") as HTMLElement;
          if (tableElement) {
            const tableHeader = tableElement.querySelector(".ant-table-header") as HTMLElement;
            const tableFooter = tableElement.querySelector(".ant-pagination") as HTMLElement;
            const headerHeight = tableHeader ? tableHeader.offsetHeight : 0;
            const footerHeight = tableFooter ? tableFooter.offsetHeight : 0;
            const containerHeight = container.offsetHeight;
            const calculatedHeight = containerHeight - headerHeight - footerHeight;
            setScrollY(calculatedHeight > 0 ? calculatedHeight - 1 : 0);
          }
        }
      };
      calculate();
      window.addEventListener("resize", calculate);
      return () => window.removeEventListener("resize", calculate);
    }
  }, [enableScroll]);

  return (
    <div ref={containerRef} className={className}>
      {React.cloneElement(children, {
        scroll: enableScroll ? { ...children.props.scroll, y: scrollY } : children.props.scroll
      })}
    </div>
  );
}; 
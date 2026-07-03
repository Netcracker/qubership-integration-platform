import { Empty } from "antd";
import type { ReactNode } from "react";

/**
 * Empty-state node for data tables. Pass as a `Table`'s `locale.emptyText` so
 * every grid shows the same compact illustration with a domain-specific line.
 */
export function tableEmpty(description: ReactNode): ReactNode {
  return (
    <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={description} />
  );
}

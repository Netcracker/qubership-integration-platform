import { Empty, Typography } from "antd";
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

/**
 * Empty-state node for a table embedded in a form, where the illustration
 * `tableEmpty` draws would outweigh the section holding it. One muted line.
 */
export function inlineTableEmpty(description: ReactNode): ReactNode {
  return (
    <Typography.Text type="secondary" style={{ display: "block", padding: 8 }}>
      {description}
    </Typography.Text>
  );
}

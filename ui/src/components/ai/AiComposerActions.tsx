import { Button, Space } from "antd";
import React from "react";
import { OverridableIcon } from "../../icons/IconProvider.tsx";

export interface AiComposerActionsProps {
  isTurnInFlight: boolean;
  onAttach: () => void;
  onSend: () => void;
  onAbort: () => void;
}

export const AiComposerActions: React.FC<AiComposerActionsProps> = ({
  isTurnInFlight,
  onAttach,
  onSend,
  onAbort,
}) => (
  <div className="ai-input__actions">
    <Space size="small">
      <Button
        type="text"
        size="small"
        icon={<OverridableIcon name="paperClip" />}
        onClick={onAttach}
        disabled={isTurnInFlight}
        aria-label="Attach file"
        title="Attach file"
      />
      <Button type="primary" disabled={isTurnInFlight} onClick={onSend}>
        Send
      </Button>
      {isTurnInFlight ? (
        <Button type="default" onClick={onAbort}>
          Stop
        </Button>
      ) : null}
    </Space>
  </div>
);

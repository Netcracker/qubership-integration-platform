import React, { useState } from "react";
import { Button, Form, Input, Modal } from "antd";
import { useModalContext } from "../../../ModalContextProvider.tsx";
import { TestingNamedParameter } from "../../../api/apiTypes.ts";
import { Script } from "../../Script.tsx";
import { isJsonDocumentValid } from "../../testing/matchers.ts";

const DEFAULT_JSON_MATCHER_PATH = "$";

function capitalize(value: string): string {
  return value.charAt(0).toUpperCase() + value.slice(1);
}

export type JsonMatcherParametersModalProps = {
  /** `schema` for a JSON Schema matcher, `sample` for a JSON one. */
  documentParameterName: string;
  parameters: TestingNamedParameter[] | null;
  onSubmit: (parameters: TestingNamedParameter[]) => void;
};

function findParameter(
  parameters: TestingNamedParameter[] | null,
  name: string,
): string {
  return parameters?.find((parameter) => parameter.name === name)?.value ?? "";
}

export const JsonMatcherParametersModal: React.FC<
  JsonMatcherParametersModalProps
> = ({ documentParameterName, parameters, onSubmit }) => {
  const { closeContainingModal } = useModalContext();
  const [path, setPath] = useState(
    () => findParameter(parameters, "path") || DEFAULT_JSON_MATCHER_PATH,
  );
  const [documentText, setDocumentText] = useState(() =>
    findParameter(parameters, documentParameterName),
  );

  // The service parses the document when it stores the matcher, so an unparseable
  // one is a 400 rather than a rule that never holds.
  const documentIsValid = isJsonDocumentValid(documentText);

  const submit = () => {
    onSubmit([
      { name: "path", value: path },
      { name: documentParameterName, value: documentText },
    ]);
    closeContainingModal();
  };

  return (
    <Modal
      title="Edit matcher parameters"
      centered
      open={true}
      onCancel={closeContainingModal}
      width="60%"
      footer={[
        <Button key="cancel" onClick={closeContainingModal}>
          Cancel
        </Button>,
        <Button
          key="submit"
          type="primary"
          disabled={!path.trim() || !documentIsValid}
          onClick={submit}
        >
          Save
        </Button>,
      ]}
    >
      <Form layout="vertical">
        <Form.Item
          label="Path"
          required
          validateStatus={path.trim() ? undefined : "error"}
          help={path.trim() ? undefined : "Enter a JSON path to match against."}
        >
          <Input
            value={path}
            aria-label="Path"
            onChange={(event) => setPath(event.target.value)}
          />
        </Form.Item>
        <Form.Item
          label={capitalize(documentParameterName)}
          required
          validateStatus={documentIsValid ? undefined : "error"}
          help={
            documentIsValid
              ? undefined
              : `Enter the ${documentParameterName} as a JSON document.`
          }
        >
          <Script
            mode="json"
            value={documentText}
            onChange={(value) => setDocumentText(value)}
            style={{ height: 320 }}
          />
        </Form.Item>
      </Form>
    </Modal>
  );
};

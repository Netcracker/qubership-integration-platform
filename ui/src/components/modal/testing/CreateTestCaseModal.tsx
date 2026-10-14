import React from "react";
import { api } from "../../../api/api.ts";
import { Element, TestCase, TestCaseRequest } from "../../../api/apiTypes.ts";
import {
  getHttpMethods,
  isHttpTrigger,
} from "../../testing/testingElements.ts";
import {
  CreateTestingEntityFormData,
  CreateTestingEntityModal,
} from "./CreateTestingEntityModal.tsx";

/** Creation defaults; a mock uses different ones, so neither set is shared. */
const DEFAULT_TIMEOUT = 120000;
const DEFAULT_ENABLED = false;

export type CreateTestCaseModalProps = {
  chainId: string;
  onCreated: (testCase: TestCase) => void;
};

function buildRequest(
  chainId: string,
  values: CreateTestingEntityFormData,
  triggers: Element[],
): TestCaseRequest {
  const trigger = triggers.find((element) => element.id === values.elementId);
  return {
    name: values.name.trim(),
    description: values.description ?? "",
    enabled: DEFAULT_ENABLED,
    // The chain reference carries the case even when no trigger is picked yet:
    // it is what scopes the case to the chain in every list.
    triggerReference: { chainId, elementId: values.elementId ?? "" },
    requestSettings: {
      queryParameters: [],
      pathParameters: [],
      message: { body: null, headers: [] },
      method: getHttpMethods(trigger)[0],
      timeout: DEFAULT_TIMEOUT,
    },
    responseValidationRules: [],
  };
}

export const CreateTestCaseModal: React.FC<CreateTestCaseModalProps> = ({
  chainId,
  onCreated,
}) => (
  <CreateTestingEntityModal<TestCase>
    chainId={chainId}
    onCreated={onCreated}
    formId="createTestCaseForm"
    title="Create Test Case"
    nameTestId="test-case-name"
    nameRequiredMessage="Enter a name for the test case."
    createFailedMessage="Failed to create a test case"
    elementLabel="Trigger"
    elementPlaceholder="Select an HTTP trigger"
    elementPredicate={isHttpTrigger}
    create={(values, triggers) =>
      api.createTestCase(buildRequest(chainId, values, triggers))
    }
  />
);

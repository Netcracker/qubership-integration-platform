import { isVsCode } from "../api/rest/vscodeExtensionApi";
import { Form, Input, Select, SelectProps } from "antd";
import { Chain, ChainCommitRequestAction, Deployment } from "../api/apiTypes";
import { capitalize } from "../misc/format-utils";
import { ChainContextData } from "./ChainPage";
import { FormData } from "./ChainProperties";

export const loadChainExtensionPropertiesToForm = (
  source: ChainContextData,
  target: FormData,
): void => {
  if (isVsCode && source?.chain) {
    target.domain =
      (source.chain.deployments?.length ?? 0 > 0)
        ? source.chain.deployments[0].domain
        : undefined;
    target.deployAction = source.chain.deployAction;
  }
};

export const readChainExtensionPropertiesFromForm = (
  source: FormData,
  target: Partial<Chain>,
): void => {
  if (isVsCode) {
    const deployAction =
      source.deployAction !== undefined
        ? ChainCommitRequestAction[
            source.deployAction as keyof typeof ChainCommitRequestAction
          ]
        : undefined;

    target.deployments = [{ domain: source.domain } as Deployment];
    target.deployAction = deployAction;
  }
};

type Props = {
  onChange: () => void;
};

export const ChainExtensionProperties: React.FC<Props> = (props) => {
  if (isVsCode) {
    const deployActionOptions: SelectProps["options"] =
      Object.keys(ChainCommitRequestAction)?.map((key) => ({
        label: capitalize(key),
        value: key,
      })) ?? [];

    return (
      <span>
        <Form.Item label="Domain" name="domain">
          <Input defaultValue="default" />
        </Form.Item>
        <Form.Item label="Deploy Action" name="deployAction">
          {/* eslint-disable-next-line react/prop-types */}
          <Select options={deployActionOptions} onChange={props.onChange} />
        </Form.Item>
      </span>
    );
  } else {
    return <></>;
  }
};

import { ExpressionParameters } from "./ExpressionParameters";

export const ConditionalParameters: React.FC = () => {
  return (
    <>
      <ExpressionParameters offset={0} label="Condition" />
      <ExpressionParameters offset={1} label="True expression" />
      <ExpressionParameters offset={2} label="False expression" />
    </>
  );
};

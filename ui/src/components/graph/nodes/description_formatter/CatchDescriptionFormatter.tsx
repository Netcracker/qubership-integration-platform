import { DescriptionFormatterProps } from "./DescriptionFormatter";

export const CatchDescriptionFormatter: React.FC<DescriptionFormatterProps> = ({
  data,
  libraryElement,
}: DescriptionFormatterProps) => {
  const priority: string | undefined = (
    data.properties as Record<string, unknown>
  )[libraryElement?.priorityProperty ?? "priority"] as string;

  return (
    <span
      style={{
        fontSize: "0.75em",
        display: "block",
        paddingLeft: 28,
        transform: "translateY(-8px)",
        lineHeight: 1,
      }}
    >
      Priority: {priority ?? ""}
    </span>
  );
};

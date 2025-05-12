const PLACEHOLDER = "—";

export function formatOptional<T>(
  value: T,
  // @ts-ignore
  formatter: (v: T) => string = (v) => v.toString(),
  placeholder: string = PLACEHOLDER,
): string {
  return value ? formatter(value) : placeholder;
}

export function formatTimestamp(
  timestamp: string | number | Date,
  millis?: boolean,
): string {
  const formatter = (t: string | number | Date) => {
    const date = new Date(t);
    const dateTimeFormatter = Intl.DateTimeFormat("en-US", {
      year: "numeric",
      month: "short",
      day: "2-digit",
      hour: "2-digit",
      minute: "2-digit",
      second: millis ? "2-digit" : undefined,
      // @ts-ignore
      fractionalSecondDigits: millis ? 3 : undefined,
    });
    return dateTimeFormatter.format(date);
  };
  return formatOptional(timestamp, formatter);
}

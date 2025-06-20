export const PLACEHOLDER = "—";

export function formatOptional<T>(
  value: T,
  formatter: (v: T) => string = (v) => String(v),
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
      fractionalSecondDigits: millis ? 3 : undefined,
      hour12: false,
    });
    return dateTimeFormatter.format(date);
  };
  return formatOptional(timestamp, formatter);
}

export function formatUTCSessionDate(date: string, millis?: boolean): string {
  return formatTimestamp(date?.concat("Z"), millis);
}

export function formatDuration(value?: number): string {
  if (value === undefined || value === null || value === 0) {
    return "";
  }

  if (value < 1000) {
    return `${value}ms`;
  }

  let seconds = value / 1000;
  if (
    seconds <
    59.995 /* because toFixed rounds value (59.996.toFixed(2) returns 60.00) */
  ) {
    return `${seconds.toFixed(2)}s`;
  }

  seconds = parseFloat(seconds.toFixed(2)); // using rounded value to properly handle minutes
  if (seconds < 3600) {
    const minutes = Math.trunc(seconds / 60);
    return `${minutes}m ${(seconds % 60).toFixed(2)}s`;
  }

  seconds = parseFloat(seconds.toFixed(0)); // using rounded value to properly handle hours and minutes
  const minutes = Math.trunc(seconds / 60);
  const hours = Math.trunc(minutes / 60);
  return `${hours}h ${minutes % 60}m ${(seconds % 60).toFixed(0)}s`;
}

export function capitalize(s: string) {
  return s && s.length > 0 ? s[0].toUpperCase() + s.slice(1).toLowerCase() : s;
}

export function formatSnakeCased(s: string) {
  return capitalize(s.split("_").join(" "));
}

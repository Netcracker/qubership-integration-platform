import * as dayjs from "dayjs";
import {Dayjs} from "dayjs";

export function toEpochMillis(v: Dayjs | null): number {
  return (v?.unix() ?? 0) * 1000;
}

export function toDayjs(v: number | null | undefined): Dayjs | undefined {
  return v ? dayjs(v) : undefined;
}

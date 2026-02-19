import dayjs from "dayjs";
import { toDayjs, toEpochMillis } from "../../src/misc/date-utils";

describe("toEpochMillis", () => {
  test("returns 0 when input is null", () => {
    expect(toEpochMillis(null)).toBe(0);
  });

  test("converts Dayjs to epoch millis using unix() * 1000", () => {
    const ts = 1700000000000;
    const d = dayjs(ts);

    expect(toEpochMillis(d)).toBe(ts);
  });
});

describe("toDayjs", () => {
  test("returns undefined when input is null", () => {
    expect(toDayjs(null)).toBeUndefined();
  });

  test("returns undefined when input is undefined", () => {
    expect(toDayjs(undefined)).toBeUndefined();
  });

  test("returns a Dayjs instance when input is a number", () => {
    const ts = 1700000000000;
    const d = toDayjs(ts);

    expect(d).toBeDefined();
    expect(d!.valueOf()).toBe(ts);
  });

  test("returns undefined when input is 0", () => {
    expect(toDayjs(0)).toBeUndefined();
  });
});

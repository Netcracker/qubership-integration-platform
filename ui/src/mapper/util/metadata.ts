import { Metadata, MetadataAware } from "../model/model.ts";

export class MetadataUtil {
  public static upsert<T>(
    metadata: Metadata | undefined,
    key: string,
    value: T,
  ): Metadata {
    return { ...metadata, [key]: value };
  }

  public static getValue<T extends MetadataAware>(
    obj: T,
    key: string,
  ): unknown {
    return obj?.metadata?.[key];
  }

  public static getTypedValue<T extends MetadataAware, V>(
    obj: T,
    key: string,
    guard: (v: unknown) => v is V,
    onFail?: (v: unknown) => V | undefined,
  ): V | undefined {
    const value = this.getValue(obj, key);
    return value === undefined
      ? undefined
      : guard(value)
        ? value
        : onFail?.(value);
  }

  public static getString<T extends MetadataAware>(
    obj: T,
    key: string,
    onFail?: (v: unknown) => string | undefined,
  ): string | undefined {
    return this.getTypedValue(obj, key, (v) => typeof v === "string", onFail);
  }

  public static setValue<T extends MetadataAware, V>(
    obj: T,
    key: string,
    value: V,
  ): T {
    return obj === undefined || obj === null
      ? obj
      : { ...obj, metadata: this.upsert(obj.metadata, key, value) };
  }
}

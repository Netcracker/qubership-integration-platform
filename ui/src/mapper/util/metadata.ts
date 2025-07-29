import { Metadata, MetadataAware } from "../model/model.ts";

export class MetadataUtil {
  public static upsert<T>(
    metadata: Metadata | undefined,
    key: string,
    value: T,
  ): Metadata {
    return { ...metadata, [key]: value };
  }

  public static getValue<T extends MetadataAware>(obj: T, key: string): unknown {
    return obj?.metadata?.[key];
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

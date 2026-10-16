import { jest } from "@jest/globals";

export function mockStorageQuota(limit: number) {
  const write = Storage.prototype.setItem;
  return jest.spyOn(Storage.prototype, "setItem").mockImplementation(function (
    this: Storage,
    key: string,
    value: string,
  ) {
    let size = key.length + value.length;
    for (let i = 0; i < this.length; i++) {
      const existingKey = this.key(i)!;
      if (existingKey !== key) {
        size += existingKey.length + this.getItem(existingKey)!.length;
      }
    }
    if (size > limit) {
      throw new DOMException("Storage is full", "QuotaExceededError");
    }
    write.call(this, key, value);
  });
}

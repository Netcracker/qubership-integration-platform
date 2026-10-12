import type React from "react";
import type { Specification, SpecificationGroup } from "../../api/apiTypes";
import { isIntegrationSystem, isSpecificationGroup } from "./ServicesTreeTable";
import type { ServiceEntity } from "./ServicesTreeTable";

export function buildServiceSubtreeIds(
  record: ServiceEntity,
  specGroupsByService: Record<string, SpecificationGroup[]>,
  specsByGroup: Record<string, Specification[]>,
): React.Key[] {
  if (isSpecificationGroup(record)) {
    const specs = specsByGroup[record.id] ?? record.specifications ?? [];
    return [record.id, ...specs.map((s) => s.id)];
  }
  if (!isIntegrationSystem(record)) {
    return [record.id];
  }
  const groups = specGroupsByService[record.id] ?? [];
  return [
    record.id,
    ...groups.map((g) => g.id),
    ...groups
      .flatMap((g) => specsByGroup[g.id] ?? g.specifications ?? [])
      .map((s) => s.id),
  ];
}

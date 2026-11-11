import { Deployment, DomainType } from "../api/apiTypes.ts";

export function toDomains(
  deployments: Pick<Deployment, "domain" | "domainType">[] | undefined,
): { name: string; type: DomainType }[] {
  const seen = new Set<string>();
  return (deployments ?? []).flatMap((deployment) => {
    if (!deployment.domain || seen.has(deployment.domain)) {
      return [];
    }
    seen.add(deployment.domain);
    return [
      {
        name: deployment.domain,
        type: deployment.domainType ?? DomainType.CLASSIC,
      },
    ];
  });
}

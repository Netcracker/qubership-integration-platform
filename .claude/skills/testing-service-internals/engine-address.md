# Which engine a test case activates

`Config.EngineAddress` names one engine, and an installation can have several: a micro domain is a Camel-K integration
with a Kubernetes service of its own, so a chain deployed there does not answer at the classic engine's address.

`internal/services/engine_address.go` resolves the engine per chain. `ResolveTrigger` reads the chain element from the
catalog, then calls `engineAddressResolver.Resolve`, which asks the catalog for `/v1/catalog/chains/{id}/deployments`
and reads the hosts out of `runtime.states`, keeping those reported `DEPLOYED` (`qip.Deployment.DeployedHosts`). The
scheme and port stay those of `EngineAddress`; only the host is replaced. `triggers.Factory.GetTrigger` takes the
address as a parameter, so one factory serves every engine.

## Rules

- **The host comes from `runtime.states`, never from `serviceName`.** The catalog documents `serviceName` as the name
  to display for errors, and it lacks the version suffix a micro domain's service gets (`qip-engine-micro` against a
  service named `qip-engine-micro-v1`). The `runtime.states` keys are the hosts the catalog dials itself:
  `LiveExchangesService` in runtime-catalog reaches an engine as `http://%s:8080/v1/engine/live-exchanges`.
- **Nothing found falls back to `EngineAddress`.** A chain the catalog reports on no engine, and a deployments endpoint
  that cannot answer, both activate on the configured address. The classic engine keeps a stable service, so a test
  still runs while the catalog's engine state cache is cold, and the lookup never becomes a new dependency of every
  run. The fallback does not cover a catalog that is down: `ResolveTrigger` reads the chain element first, so the case
  fails there with `Failed to resolve trigger: failed to get chain element: …`.
- **An ambiguous chain is resolved, not refused.** A chain on several domains is a configuration nobody meant to make.
  `servingDomains` puts a classic domain first, because that is where such a chain was activated before, and orders
  the rest by name, so an installation resolves the same way on every run. The choice is logged at WARN with every
  domain named. Hosts are sorted too, so a domain of several replicas resolves to the same engine every time.
- **A resolved host is not retried on another.** Activating a trigger is not idempotent: a chain that creates an order
  would create a second one. A run that reached a host and got nothing back is a failed run.

## Limits of the answer

The hosts are only as fresh as the engine state cache of the catalog. A domain removed moments ago is still reported,
and a case activated in that window fails against a host that no longer exists. The failure names the address, and the
next run resolves elsewhere once the cache catches up. Every reader of this catalog API sees the same lag.

The platform mostly prevents a chain from being deployed on two domains, so the ambiguous branch is rare rather than
dead. `DeploymentService.checkHttpTriggers` in runtime-catalog refuses a classic deployment whose HTTP trigger path is
already registered on another domain, with this message:

```text
Found similar triggers paths registered on other domains or by other chains on this domain
```

`CustomResourceController` deploys a Camel-K resource without that check, so deploying to a classic domain first and to
a micro domain second leaves the chain on both.

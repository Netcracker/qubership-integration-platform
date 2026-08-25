---
description: Working agreements for how changes are proposed and scoped in this repository.
applyTo: "**/*"
---

## Keep the change minimal

Add a defensive mechanism — an advisory lock, a cache, a size limit, a null
guard, a data backfill — only when a real failure motivates it: a reported
incident, a failing test, or a case the user named. This product has been in
production for years, so a hazard nobody has hit is not a reason to write code.
Before handing work back, reread the diff and drop whatever guards a
hypothetical.

## Report before you fix

When a review, an investigation, or a batch of agents produces findings,
present them and stop. Fix them after the user says which ones to fix: some
findings are deliberate decisions, and some are worth reverting rather than
patching.

## Squash your own migrations while the branch is unpushed

A Flyway migration or an import-file migration that exists only on an unpushed
branch is still editable. Edit it in place and keep the branch down to one new
migration instead of layering a second one on top. Never add compatibility code
between two migrations that no deployment has ever run.

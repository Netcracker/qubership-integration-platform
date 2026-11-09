---
description: Trigger for the change-description-authoring skill, scoped to pull requests and issues.
applyTo: "**"
---

Load `change-description-authoring`, with `english-developer-style`, before writing or rewording
a pull request title or description, or the title or body of a GitHub issue. A commit message
needs only `english-developer-style` and the Conventional Commits format, so do not load
`change-description-authoring` for one, whatever the `english-developer-style` rule says about
installed authoring skills.

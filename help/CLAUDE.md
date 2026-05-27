# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Repository Purpose

Documentation-only repository for the [Qubership Integration Platform](https://github.com/Netcracker/qubership-integration-platform) (QIP). Contains help documents consumed by the QIP web UI and VSCode extension. There is no source code to build or test.

## CI/CD

- **Super-linter** runs on all pushes and PRs (`.github/workflows/super-linter.yaml`), linting markdown and other files using shared config from `netcracker/.github`
- **Link checker** validates URLs in documentation
- **PR title linting** and **conventional commits** checks enforce commit message format

## Conventions

- **Commits/PRs**: Follow [Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0/) (e.g. `feat:`, `fix:`, `docs:`)
- **Directory naming**: Numeric prefixes with double underscores for ordering: `00__Overview`, `01__Chains`, etc.
- **Document structure**: Each topic gets its own directory with a main `.md` file and an optional `img/` subdirectory for images (SVGs preferred)
- **EditorConfig**: Spaces for indentation, UTF-8, final newlines. Markdown files preserve trailing whitespace.

## Documentation Sections

All content lives under `docs/`:

| Prefix | Section | Content |
|--------|---------|---------|
| `00__` | Overview | Platform concepts, token processing, Apache Camel context, access control |
| `01__` | Chains | Graph editor, QIP elements library (routing, files, triggers, services, transformation, senders, grouping), snapshots, deployments, sessions, logging, masking, properties |
| `02__` | Services | External/inner cloud services, implemented services, context |
| `03__` | Admin Tools | Domains, variables, audit, sessions, live exchanges |
| `04__` | Dev Tools | MaaS integration, diagnostics |

## Platform Context

This is the **documentation repository** for the Qubership Integration Platform (QIP). See `~/.claude/CLAUDE.md` for the full platform map.

### Consumers

- **qubership-integration-ui**: Fetches docs at build time via `fetch-docs` script, renders in help browser
- **qubership-integration-vscode-extension**: Indexes docs for searchable in-extension documentation

### What the Docs Cover

The documentation describes the full QIP user experience: chain design (graph editor, element library with ~70+ element types), service configuration, deployment management, session monitoring, admin tools (variables, domains, audit), and developer tools (MaaS, diagnostics). When modifying platform behavior in other repos, corresponding docs here may need updating.

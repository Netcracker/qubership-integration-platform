# qubership-integration-help

This directory stores helper documents for the [Cloud Integration Platform](../README.md) (CIP).

CIP is an open-source integration solution built on [Apache Camel](https://camel.apache.org/index.html). It enables integration between diverse systems while handling data transformation, process orchestration and mapping between different system formats.

The documents from this directory are consumed by:

- [Qubership Integration UI](../ui) — web interface for designing and managing integration chains
- [Qubership Integration VSCode Extension](../vscode-extension) — Visual Studio Code extension for working with QIP

## Documentation Structure

All documentation is located in the [`docs/`](docs/) directory and organized into the following sections:

| Section                                      | Description                                                                                                                              |
|----------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------|
| [Overview](docs/00__Overview/)               | Start here: getting started, Apache Camel context, general functions, architecture, glossary                                             |
| [Chains](docs/01__Chains/)                   | Chain list, graph editor, element library, snapshots, deployments, sessions, logging, masking, properties, testing                       |
| [Services](docs/02__Services/)               | External, inner cloud, implemented, context, and MCP services                                                                            |
| [Admin Tools](docs/03__Admin_Tools/)         | Domains, variables, audit, import instructions, sessions, access control, design templates, live exchanges, testing                      |
| [Dev Tools](docs/04__Dev_Tools/)             | MaaS integration, diagnostic tools                                                                                                       |
| [How To](docs/05__How_To/)                   | Step-by-step guides for recurring tasks, such as retrying a session, switching to MaaS, or restricting chain access                      |
| [Observability](docs/06__Observability/)     | Platform logging, metrics, and session monitoring                                                                                        |
| [Troubleshooting](docs/07__Troubleshooting/) | Symptoms, likely causes, and what to check, plus UI errors and the error reference                                                       |
| [Features](docs/08__Features/)               | Platform mechanisms and the parameters that control them: system properties, token processing, retention settings, database multitenancy |

**Overview** holds the orientation material the rest of the documentation assumes. Sections 01 through 04 follow the product's UI areas, so a page describing a screen belongs there. The last four are organized by what the reader is trying to do: a repeatable task with a beginning and an end goes under **How To**, a record the platform writes about its own work goes under **Observability**, a symptom you are diagnosing goes under **Troubleshooting**, and a mechanism the platform performs without a screen to drive it goes under **Features**.

## Contribution

Commits and pull requests should follow the [Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0/) specification.

Documents are written in Markdown. The naming convention uses numeric prefixes with double underscores for ordering (e.g. `00__Overview`, `01__Chains`). Each topic has its own directory with a main `.md` file and an optional `img/` folder for images.

Each section has a landing page, such as `how_to.md` or `features.md`, that links to the pages under it. When you add, move, or remove a page, update that list and any links that point to the old path. A term that a new page introduces gets an entry in the [Glossary](docs/00__Overview/4__Glossary/readme.md).

## Licensing

This software is licensed under Apache License Version 2.0. License text is located in the [LICENSE](../LICENSE) file.

## Related modules

- [qubership-integration-platform](../README.md) — core deployment guide
- [qubership-integration-ui](../ui) — web UI
- [qubership-integration-vscode-extension](../vscode-extension) — VSCode extension

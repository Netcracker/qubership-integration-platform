# qubership-integration-help

This repository stores helper documents for the [Qubership Integration Platform](https://github.com/Netcracker/qubership-integration-platform) (QIP).

QIP is an open-source integration solution built on [Apache Camel](https://camel.apache.org/). It enables integration between diverse systems while handling data transformation, process orchestration and mapping between different system formats.

The documents from this repository are consumed by:

- [Qubership Integration UI](https://github.com/Netcracker/qubership-integration-ui/) — web interface for designing and managing integration chains
- [Qubership Integration VSCode Extension](https://github.com/Netcracker/qubership-integration-vscode-extension) — Visual Studio Code extension for working with QIP

## Documentation Structure

All documentation is located in the [`docs/`](docs/) directory and organized into the following sections:

| Section | Description |
|---------|-------------|
| [Overview](docs/00__Overview/) | Platform concepts: token processing, Apache Camel context, chain configuration, general functions, access control |
| [Chains](docs/01__Chains/) | Chain graph editor, QIP elements library (routing, files, triggers, services, transformation, senders), triggers and properties |
| [Services](docs/02__Services/) | External and inner cloud services, implemented services, context configuration |
| [Admin Tools](docs/03__Admin_Tools/) | Domains, variables, audit, sessions, live exchanges |
| [Dev Tools](docs/04__Dev_Tools/) | MaaS integration, diagnostic tools |

## Contribution

Commits and pull requests should follow the [Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0/) specification.

Documents are written in Markdown. The naming convention uses numeric prefixes with double underscores for ordering (e.g. `00__Overview`, `01__Chains`). Each topic has its own directory with a main `.md` file and an optional `img/` folder for images.

## Licensing

This software is licensed under Apache License Version 2.0. License text is located in the [LICENSE](LICENSE) file.

## Related Repositories

- [qubership-integration-platform](https://github.com/Netcracker/qubership-integration-platform) — core deployment guide
- [qubership-integration-ui](https://github.com/Netcracker/qubership-integration-ui/) — web UI
- [qubership-integration-vscode-extension](https://github.com/Netcracker/qubership-integration-vscode-extension) — VSCode extension


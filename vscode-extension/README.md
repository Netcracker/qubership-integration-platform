# Qubership Integration Visual Studio Code Extension

This is Visual Studio Code extension based
on [Qubership Integration Platform - UI](https://github.com/Netcracker/qubership-integration-ui)
project to manipulate chain configurations offline. To run configurations you still need the other part
of [Qubership Integration Platform](https://github.com/Netcracker/qubership-integration-platform).

## Service files

A service the extension creates lives in a folder named after its id, in a file whose name states the service type:
`<id>.external-service.qip.yaml`, `<id>.internal-service.qip.yaml`, `<id>.implemented-service.qip.yaml`,
`<id>.context-service.qip.yaml`, or `<id>.mcp-service.qip.yaml`. One folder may hold several services, the workspace
root included. The type is chosen when the service is created and is read-only from then on. The QIP explorer groups
services by type.

The extension also reads the older type-less `<id>.service.qip.yaml`, which keeps the type inside the document. Editing
such a service rewrites it under the typed name and deletes the old file, so a project migrates as you edit it and git
records a rename. The editor tab keeps showing the old file name after that first edit. That is cosmetic: the service
stays editable, and the tab picks up the new name the next time you open it. Two kinds of service stay in the old
format: one whose document states no type at all, and one whose id contains a dot, which no typed name can spell back.

File names and schema URLs are configurable per app in a `.config.qip.yaml`. See `.config.qip.yaml.example` for the full
set of keys.

## Build

This application should be built by Visual Studio Code itself (usually F5 hotkey at opened project in Visual Studio Code).

## Contribution

For the details on contribution, see [Contribution Guide](../CONTRIBUTING.md). For details on reporting of security issues
see [Security Reporting Process](../SECURITY.md).

Commits and pool requests should follow [Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0/) strategy.

## Licensing

This software is licensed under Apache License Version 2.0. License text is located in [LICENSE](../LICENSE) file.

## Additional Resources

- [Qubership Integration Platform](https://github.com/Netcracker/qubership-integration-platform) — core deployment
  guide.

# Qubership Integration Platform - UI

This is a web-based frontend application for Qubership Integration Platform.
It provides a unified user interface to manage integration logic.

## Installation

The application is built using [React](https://react.dev/) and [Ant Design](https://ant.design/) libraries and requires
npm to build and run.

## Dependencies

This application relies
on [Design-Time Catalog](https://github.com/Netcracker/qubership-integration-designtime-catalog),
[Runtime Catalog](https://github.com/Netcracker/qubership-integration-runtime-catalog),
[Variables Management](https://github.com/Netcracker/qubership-integration-variables-management),
[Sessions Management](https://github.com/Netcracker/qubership-integration-sessions-management),
and [Engine](https://github.com/Netcracker/qubership-integration-engine) services.

## Documentation

This application includes a built-in documentation system. When used as an npm library, host applications can integrate
and customize the documentation. See the [Documentation Integration Guide](docs/DOCUMENTATION_INTEGRATION.md) for
details.

## OpenCode chat

The **OpenCode** icon opens a separate chat alongside Rocky. It connects directly to OpenCode 1.19.0 through the official
`@opencode-ai/sdk` client. The server selects its configured agent, model, and working directory.

For local testing, install the binary for your platform from the
[OpenCode 1.19.0 release](https://github.com/anomalyco/opencode/releases/tag/v1.19.0), then start the server:

```bash
opencode serve --hostname 127.0.0.1 --port 4096 --cors http://localhost:5173
```

Use the actual UI origin in `--cors`. Open **OpenCode**, enter the **Server URL**, and select **Connect**. The default URL
is `http://127.0.0.1:4096`. Set `VITE_OPENCODE_URL` or pass `opencodeUrl` to `configure()` to change the initial URL.

Remote servers require HTTPS and a CORS configuration that permits the UI origin. If the server uses
`OPENCODE_SERVER_PASSWORD`, enter its username and password in the connection form. The default username is `opencode`.
Credentials stay in memory; the UI saves only the selected session ID, scoped to the server URL.

The chat supports streamed replies, session history, stopping a turn, tool activity, permission requests, and questions
with choices or a custom answer. **Clear** deletes the selected OpenCode session and starts a new one after confirmation.

Attach up to five files, each no larger than 10 MB: TXT, Markdown, JSON, YAML, CSV, PDF, PNG, JPEG, GIF, or WebP. Text files
are sent as text attachments. The configured model must support image or PDF input to interpret those attachments.
Attachment errors retain the draft and selected files for retry.

After reconnection, an in-progress text part is restored from history and waits for its next server snapshot. New text
parts stream normally.

## Contribution

For the details on contribution, see [Contribution Guide](../CONTRIBUTING.md). For details on reporting of security issues
see [Security Reporting Process](../SECURITY.md).

Commits and pool requests should follow [Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0/) strategy.

## Licensing

This software is licensed under Apache License Version 2.0. License text is located in [LICENSE](../LICENSE) file.

## Additional Resources

- [Qubership Integration Platform](https://github.com/Netcracker/qubership-integration-platform) — core deployment
  guide.

# Qubership Integration VSCode Extension

This is vscode extension based on [Qubership Integration Platform - UI](https://github.com/Netcracker/qubership-integration-ui)
project to manipulate chain configurations offline. To run configurations you still need the other part of [Qubership Integration Platform](https://github.com/Netcracker/qubership-integration-platform).

## Build

This application should be built by Visual Studio Code itself (usually F5 hotkey at opened project in vscode).

## Testing

Run the comprehensive test suite (17 tests):

```bash
./run-tests.sh
```

Or use npm:

```bash
npm test
```

All tests cover configuration system, external extension integration, and error handling. See [Test Summary](TEST_SUMMARY.md) for details.

## Extension API

This extension provides a public API that allows other VS Code extensions to integrate with QIP functionality. You can use the simple `ConfigApiProvider` singleton to:

- Load custom configuration files
- Register configurations programmatically
- Access and manage QIP configurations

**Quick Example:**

```typescript
import { ConfigApiProvider } from "@netcracker/qip-vscode-extension";

const configApi = ConfigApiProvider.getInstance();
await configApi.loadConfigFromPath(configUri);
```

For detailed API documentation and usage examples, see:

- [Extension API Guide](EXTENSION_API.md) - API reference and examples
- [Quick Start Guide](EXTENSION_API_SIMPLE_EXAMPLE.md) - Simple integration guide
- [Configuration System](CONFIG_SYSTEM.md) - How configuration system works

## Contribution

For the details on contribution, see [Contribution Guide](CONTRIBUTING.md). For details on reporting of security issues
see [Security Reporting Process](SECURITY.md).

Commits and pool requests should follow [Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0/) strategy.

## Licensing

This software is licensed under Apache License Version 2.0. License text is located in [LICENSE](LICENSE) file.

## Additional Resources

- [Qubership Integration Platform](https://github.com/Netcracker/qubership-integration-platform) — core deployment
  guide.

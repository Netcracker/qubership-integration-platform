# Qubership Integration VSCode Extension

This extension provides integration capabilities for Qubership platform within Visual Studio Code.

## Features

- Chain management and editing
- Service management
- Specification import and management
- Real-time collaboration
- Offline editing capabilities

## Specification Import

The extension supports importing various API specifications:

### Supported Formats

- **OpenAPI/Swagger** (.json, .yaml, .yml) - REST API specifications
- **WSDL** (.wsdl, .xsd) - SOAP web service definitions with automatic operation extraction
- **GraphQL** (.graphql, .graphqls) - GraphQL schema definitions
- **gRPC** (.proto) - Protocol Buffer service definitions
- **AsyncAPI** (.json, .yaml, .yml) - Asynchronous API specifications

### Import Process

1. **Create a Service** - First, create a service in the extension
2. **Import Specifications** - Use the import functionality to add specification files
3. **Automatic Processing** - The extension automatically:
   - Detects the specification protocol
   - Validates file compatibility
   - Creates proper file structure
   - Generates metadata

### File Structure

After import, specifications are organized as follows, matching the exported service format:

```
workspace/
├── systems.qip.yaml
└── {serviceId}/
    ├── {serviceId}.service.qip.yaml
    ├── {serviceId}-{groupName}.specification-group.qip.yaml
    ├── {serviceId}-{groupName}-{version}.specification.qip.yaml
    └── resources/
        └── source-{specificationId}/
            └── {originalFileName}
```

This structure ensures compatibility with services exported from the backend and allows seamless viewing and editing of imported specifications.

### Usage Examples

#### Import OpenAPI Specification

```typescript
// Import OpenAPI specification
const files = [openApiFile]; // File object from file input
const result = await api.importSpecificationGroup(
  "system-123",
  "My API",
  files,
  "HTTP"
);
```

#### Import WSDL Specification

```typescript
// Import WSDL specification with XSD files
const files = [wsdlFile, xsdFile]; // WSDL and XSD files
const result = await api.importSpecificationGroup(
  "system-456",
  "SOAP Service",
  files,
  "SOAP"
);
```

#### SOAP Service Features

The extension provides comprehensive SOAP service support:

- **Automatic WSDL parsing** - Extracts service names, operations, and metadata
- **Operation extraction** - Creates operation definitions from WSDL port types
- **SOAP endpoint configuration** - Sets up proper SOAP environment settings
- **XML format support** - Handles both WSDL and XML service definitions

For detailed SOAP support information, see [SOAP_SUPPORT.md](SOAP_SUPPORT.md).

#### Check Import Status

```typescript
// Check import progress
const status = await api.getImportSpecificationResult(result.id);
if (status.done) {
  console.log("Import completed:", status.specificationGroupId);
}
```

### Error Handling

The import process handles various error scenarios:

- **Unsupported file types** - Files with unsupported extensions are rejected
- **Protocol mismatch** - Different protocols between system and import are detected
- **Missing main source** - WSDL files without binding/service elements
- **Archive extraction failures** - Corrupted or unsupported archive formats

## Development

### Building

```bash
npm install
npm run compile-web
```

### Testing

```bash
npm test
```

### Running the Extension

1. Open the project in VS Code
2. Press F5 to start debugging
3. A new VS Code window will open with the extension loaded

## Architecture

The extension consists of:

- **Web Extension** - Main extension logic
- **UI Components** - React-based user interface
- **File System Integration** - Local file management
- **API Integration** - Communication with Qubership platform

## Contributing

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Add tests for new functionality
5. Submit a pull request

## License

This project is licensed under the Apache License 2.0.

## Additional Resources

- [Qubership Integration Platform](https://github.com/Netcracker/qubership-integration-platform) — сore deployment
  guide.

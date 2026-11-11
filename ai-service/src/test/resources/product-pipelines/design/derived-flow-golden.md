# Integration Design Specification

## Integration Process

### Integration flow for CIP Chain - Orders

```mermaid
sequenceDiagram
    autonumber
    participant p_client as Client
    participant p_orders_api as Orders API
    p_client->>p_orders_api: create order
```

#### Process Steps

| Process Step | Description |
|--------------|-------------|
| step-1 | create order |

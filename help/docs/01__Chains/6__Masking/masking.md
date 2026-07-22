# Masking
## Description

---
This tab allows to apply logging masking for specific parameters in order to protect the data from unauthorized access.

## User Interface

---
### View Masking Fields Table
The table of masking fields consists of the following columns and elements:

- **Field** - masking field's name, that is going to be masked. For editing, hover the cursor over the name and click on it.
- **Created By** - field creator's username (hidden by default for <ins>VS Code Extension</ins>).
- **Created At** - shows date and time when a field was created (hidden by default for <ins>VS Code Extension</ins>).
- **Modified By** - name of the user, who last modified the field (hidden by default for <ins>VS Code Extension</ins>).
- **Modified At** - shows date and time when a field was updated (hidden by default for <ins>VS Code Extension</ins>).

At the top of the table the following options are available:
- **Search masked field** - search box, provides ability to find respective data in the table.
- ![setting](img/setting.svg) - opens pop-up with table properties that allows adjusting visibility and order of the columns
- ![delete](img/delete.svg) - deletes selected masked fields.
- ![plus](img/plus.svg) - allows to add masked  field.

### Add Field for Masking
To secure sensitive data use the **action menu** to  **Add New Masked Field** ![plus](img/plus.svg). Enter a name. The field will be masked at every level of JSON, XML, etc., during chain execution. Masked fields affect logs and sessions by hiding the original data.

> ℹ️ **Notes:**
> - Masked fields can be configured to include parameters from the exchange object, including headers, exchange properties and body.
> - Masking is only supported for **primitives** or **array of primitives** on any nesting level.
> - Masking is supported for **json**, **xml**, **soap+xml**, **json-patch+json** and **x-www-form-urlencoded** content types only (type is being specified via *Content-Type* header).
> - Data for fields, that were selected for masking will be replaced with six symbols "*" (e.g.: ******).
> - All fields in the chain with the specified name are going to be masked. If there are two or more fields with the same name in the chain, then masking is going to be performed for all of them.
> - When masking settings are updated for specific chain, it is required to redeploy the chain to apply the changes. Chain itself will be marked with **"Unsaved Changes"** label.
> - Session data, that is going to be logged to Graylog will also be masked accordingly.
> - Field's configuration is stored at snapshot. This means, that when chain's snapshot is being reinstated to the older versions, field configuration is being reinstated to the accorded version as well.
> - When using sub-chains (via [Chain Call](../1__Graph/1__Elements_Library/1__Routing/6__Chain_Call/chain_call.md) element), the masked fields list is determined by combining the fields defined in the parent chain with those specified in the sub-chain. This essentially means the **sub-chain inherits the masked fields from its parent chain**.

### Delete Field(s)
To delete masking field(s) select the items by checkbox and click ![delete](img/delete.svg).

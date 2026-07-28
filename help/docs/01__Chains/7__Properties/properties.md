# Properties
## Description

---
This tab allows to fill in the properties of the chain.

## User Interface

---
The following properties are available:

### <ins>Web UI</ins>
* **Name** - the user-defined chain name.
* **Labels** - tags used for categorization or filtering.
* **Description** - general description.
* **Business Description** - non-tech description for better understanding.
* **Assumptions** - allowances that must be true for this chain to work.
* **Out of Scope** - statements not covered by the chain.

### <ins>VS Code Extension</ins>
* **Name** - the user-defined identifier for the chain.
* **Path** - the chain's folder path. Separate levels with `/` (for example, `payments/processing`). Each segment must not contain any of the following characters: `/ : * ? " < > | , ; \`.
* **Labels** - tags used for categorization or filtering.
* **Description** - general description.
* **Business Description** - non-tech description for better understanding.
* **Assumptions** - allowances that must be true for this chain to work.
* **Out of Scope** - statements not covered by the chain.
* **Domain** (mandatory) - specified domain name for chain to be deployed.
* **Deploy Action** - allows to select the following actions:
  * _**None**_ - the chain will be saved as a draft.
  * _**Snapshot**_ - the chain will be saved as a draft with created snapshot.
  * _**Deploy**_ - the chain will be deployed.

Once all necessary parameters are filled in, click "Apply" button to save the updates.

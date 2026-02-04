# Masking
## Description

---
This tab allows to apply logging masking for specific parameters in order to protect the data from unauthorized access.

## User Interface

----
### View Masking Fields Table

The table of masking fields consists of the following columns and elements:

<u>_Web UI_</u>

- **Field** - masking field's name, that is going to be masked. For editing, hover the cursor over the name and click on it.
- **Created By** - field creator's username.
- **Created At** - shows date and time when a field was created.
- **Modified By** - name of the user, who last modified the field.
- **Modified At** - shows date and time when a field was updated.

<u>_VS Code Extension_</u>

-  **Field** - masking field's name, that is going to be masked. For editing, hover the cursor over the name and click on it.

### Add Field for Masking

To secure sensitive data use the **action menu** marked with <img src="docs/01__Chains/6__Masking/img/more.svg" width="20" height="20"> to  **Add New Masked Field** <img src="docs/01__Chains/6__Masking/img/plus.svg" width="20" height="20">. Enter a name. The field will be masked at every level of JSON, XML, etc., during chain execution. Masked fields affect logs and sessions by hiding the original data.

<div style="background-color: #e7f3fe; border-left: 6px solid #2196F3; padding: 10px">
       <b>Notes:</b><br>
        <ol>
	        <li>Masked fields can be configured to include parameters from the exchange object, including headers, exchange properties and  body. </li>
            <li>Masking is only supported for <b>primitives</b> or <b>array of primitives</b> on any nesting level.</li>
            <li>Masking is supported for <b>json</b>, <b>xml</b>, <b>soap+xml</b>, <b>json-patch+json</b> and <b>x-www-form-urlencoded</b> content types only (type is being specified via <i>Content-Type</i> header) .</li>
            <li>Data for fields, that were selected for masking will be replaced with six symbols "*" (e.g.: ******).</li>
            <li>All fields in the chain with the specified name are going to be masked. If there are two or more fields with the same name in the chain, then masking is going to be performed for all of them.</li>
            <li>When masking settings are updated for specific chain, it is required to redeploy the chain to apply the changes. Chain itself will be marked with <b>"Unsaved Changes"</b> label. </li>
            <li>Session data, that is going to be logged to Graylog will also be masked accordingly.</li>
            <li>Field's configuration is stored at snapshot. This means, that when chain's snapshot is being reinstated to the older versions, field configuration is being reinstated to the accorded version as well.</li>    
            <li>When using sub-chains (via <a href="docs/01__Chains/1__Graph/1__QIP_Elements_Library/1__Routing/6__Chain_Call/chain_call.md">Chain Call</a> element), the masked fields list is determined by combining the fields defined in the parent chain with those specified in the sub-chain. This essentially means the <b>sub-chain inherits the masked fields from its parent chain</b>.</li>
</ol>
</div>

### Delete Field(s)

To delete masking field(s) select the items by checkbox and click <img src="docs/01__Chains/6__Masking/img/delete.svg" width="20" height="20">.
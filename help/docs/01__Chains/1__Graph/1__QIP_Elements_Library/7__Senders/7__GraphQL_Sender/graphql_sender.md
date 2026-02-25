# GraphQL Sender
## Description

---
**GraphQL Sender** allows to execute query or mutation on the particular GraphQL server, connected via HTTP.

## User Interface

---
### "Parameters" Tab
#### Common Parameters
| Parameter           | Mandatory | Data Type | Description                                                                                                                 | Sample                      |
| ------------------- | :-------- | :-------- | --------------------------------------------------------------------------------------------------------------------------- | --------------------------- |
| Enable M2M Security | M         | Boolean   | Specifies whether M2M token should be used to make a query.                                                                 | N/A                         |
| GraphQL server URI  | M         | String    | GraphQL server URI.                                                                                                         | https://example.com/graphql |
| Operation Name      | O         | String    | The query or mutation name. Optional if query contains single operation.                                                    | GetTicketById               |
| Query               | M         | String    | GraphQL query, required to be executed.<br><br>**ℹ️Note**: More than one query or mutation can be entered at the same time. | N/A                         |
| Variables JSON      | O         | String    | The JsonObject instance, that contains the operation variables. Camel Exchange variables can also be used.                  | N/A                         |
| Connection timeout  | O         | Number    | Specifies connection timeout in milliseconds.<br>**Default value:** 120000                                                  | 12000                       |

<details open><summary>Query sample</summary>
<b>Query:</b>
<pre style="background-color: #F5F5F7"><code  style="color: #000000">query getVehicle { 
    vehicles(count: $countvar) {
        id
        type
        modelCode
    }
}
</code></pre>


<b>Variables:</b>
<pre style="background-color: #F5F5F7"><code  style="color: #000000">{
    "countvar": 2
}
</code></pre>
</details>

#### Advanced Parameters
| Parameter                    | Mandatory | Data Type | Description                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                 | Sample           |
| ---------------------------- | :-------- | :-------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------- |
| External call                | M         | Boolean   | Checkbox, that specifies if called URI is registered on External gateway.<ul><li>**If marked** - request will be performed through Egress Gateway. Both HTTP and HTTPS are allowed.</li><li>**If not marked** - URI will be called directly. Only HTTP is applicable for this case.</li></ul>                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               | N/A              |
| Reuse established connection | M         | Boolean   | Checkbox, that controls ability of using same connection for multiple requests.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                             | N/A              |
| Propagate context            | M         | Boolean   | Checkbox, that defines if context to special ("Technical") headers before sending message will be propagated or not.<ul><li>If **checked** (default): context to this call will be propagated, which will lead to the reinstatement of all technical headers, that are stored in context.</li><li>If **unchecked**: call propagation will be switched off, hence values of technical headers, that are stored in the context won't be reinstated.</li></ul>Additionally, when **"Propagate context"** is checked, **"Override Technical Context Headers"** table becomes available to the user. This table allows to override the value for the specific header, that has been propagated from context. <br><br>**ℹ️Note**: For the actual list of technical headers, please, contact system administrator. | N/A              |
| Receive correlation id       | O         | Boolean   | Checkbox, that enables ability to define correlation id.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                    | N/A              |
| Correlation Id Position      | O         | List      | Position of correlation id in request. Possible values: <ul><li>Header</li><li>Body</li></ul>Visible if **"Receive correlation id"** checkbox is marked.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                    | Header           |
| Correlation Id Key           | O         | String    | The exact name of the header or body parameter, that holds correlation id value.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                            | correlationIdKey |

#### Metadata
| Parameter   | Mandatory | Data Type | Description                                                | Sample                              |
| ----------- | :-------- | :-------- | ---------------------------------------------------------- | ----------------------------------- |
| Name        | M         | String    | Name of the element.                                       | GrapQL Server                       |
| Description | O         | String    | Free text field, that contains description of the element. | Gets trouble ticket name and status |

## Constraints

---
There are no specific constraints for the element.

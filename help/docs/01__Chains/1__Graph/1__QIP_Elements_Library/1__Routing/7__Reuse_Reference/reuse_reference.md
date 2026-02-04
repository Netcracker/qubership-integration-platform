# Reuse Reference
## Description

---
**Reference** element allows to connect the elements with a reusable chain flow, that is configured within [Reuse](docs/01__Chains/1__Graph/1__QIP_Elements_Library/1__Routing/2__Reuse/reuse.md) container to avoid doubling the logic.


## User Interface

---
### "Parameters" Tab
#### Common Parameters
| Parameter                                       | <div style="width:75px">Mandatory</div> | <div style="width:75px">Data Type</div> | Description                                                                                                                                                         | Sample                             |
| ----------------------------------------------- | :-------------------------------------- | :-------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------- |
| <div style="width:150px">Reuse Reference	</div> | M                                       | List                                    | <div style="width:400px">Specifies the [Reuse](docs/01__Chains/1__Graph/1__QIP_Elements_Library/1__Routing/2__Reuse/reuse.md) container to be connected with.</div> | <div style="width:350px">N/A</div> |

### "Parameters" Tab
#### Metadata
| Parameter                                   | <div style="width:75px">Mandatory</div>    | <div style="width:75px">Data Type</div>   | Description                                                               | Sample                                            |
|---------------------------------------------|:-------------------------------------------|:------------------------------------------|---------------------------------------------------------------------------|---------------------------------------------------|
| <div style="width:150px">Name</div>         | M                                          | String                                    | <div style="width:400px">Name of the element.	</div>                      | <div style="width:350px">Reference</div>          |
| <div style="width:150px">Description</div>  | O                                          | String                                    | <div style="width:400px">Free text field for element description.	</div>  | <div style="width:350px">Validate response</div>  |

## Constraints

---
Please consider next constraints:
- Reference can be established only with the reuse containers, that are configured within the current chain.
- Only a single reuse container could be referenced at once.
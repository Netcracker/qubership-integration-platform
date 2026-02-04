# Reuse
## Description

---
**Reuse** is a container-type element, that can hold a "repeatable" part of the chain logic, which would be available for reuse by other chain parts via [Reuse Reference](docs/01__Chains/1__Graph/1__QIP_Elements_Library/1__Routing/7__Reuse_Reference/reuse_reference.md) element. Simply configure a chain part within Reuse container, that is designed to be referred multiple times and then call it across the main chain when required. This might be helpful for building Error Handling or Validation logics.


## User Interface

---
### "Parameters" Tab
#### Metadata
| Parameter                                   | <div style="width:75px">Mandatory</div>    | <div style="width:75px">Data Type</div>   | Description                                                              | Sample                                                    |
|---------------------------------------------|:-------------------------------------------|:------------------------------------------|--------------------------------------------------------------------------|-----------------------------------------------------------|
| <div style="width:150px">Name</div>         | M                                          | String                                    | <div style="width:400px">Name of the element.	</div>                     | <div style="width:350px">Reuse</div>                      |
| <div style="width:150px">Description</div>  | O                                          | String                                    | <div style="width:400px">Free text field for element description.	</div> | <div style="width:350px">Error Handling logic reuse</div> |

## Constraints

---
There are no specific constraints for the element.
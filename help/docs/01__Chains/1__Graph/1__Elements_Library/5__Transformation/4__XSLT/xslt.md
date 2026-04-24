# XSLT
## Description

---
**XSLT** element allows to build a proper message from source XML, utilizing XSLT template.

## User Interface

---
### "Parameters" Tab
#### Common Parameters
| Parameter                          | Mandatory | Data Type | Description                                                      | Sample        |
| ---------------------------------- | :-------- | :-------- | ---------------------------------------------------------------- | ------------- |
| Template name (path to local file) | M         | String    | Path to the template, which must be stored in temporary storage. | /tmp/file.xsl |

#### Metadata
| Parameter   | Mandatory | Data Type | Description                                                | Sample                                       |
| ----------- | :-------- | :-------- | ---------------------------------------------------------- | -------------------------------------------- |
| Name        | M         | String    | Name of the element.                                       | Transform account data                       |
| Description | O         | String    | Free text field, that contains description of the element. | Utilizes the template from the temp storage. |

## Constraints

---
There are no specific constraints for the element.

# XSLT
## Description

---
**XSLT** element allows to build a proper message from source XML, utilizing XSLT template.

## User Interface

---
### "Parameters" Tab
#### Common Parameters
| Parameter                                                    | <div style="width:75px">Mandatory</div>    | <div style="width:75px">Data Type</div>  | Description                                                                                       | Sample                                        |
|--------------------------------------------------------------|:-------------------------------------------|:-----------------------------------------|---------------------------------------------------------------------------------------------------|-----------------------------------------------|
| <div style="width:150px">Template name (path to local file)  | M                                          | String                                   | <div style="width:400px">Path to the template, which must be stored in temporary storage.	</div>  | <div style="width:350px">/tmp/file.xsl</div>  |

#### Metadata
| Parameter                            | <div style="width:75px">Mandatory</div> | <div style="width:75px">Data Type</div> | Description                                                                                | Sample                                                                      |
| ------------------------------------ | :-------------------------------------- | :-------------------------------------- | ------------------------------------------------------------------------------------------ | --------------------------------------------------------------------------- |
| <div style="width:150px">Name        | M                                       | String                                  | <div style="width:400px">Name of the element.</div>                                        | <div style="width:350px">Transform account data</div>                       |
| <div style="width:150px">Description | O                                       | String                                  | <div style="width:400px">Free text field, that contains description of the element.	</div> | <div style="width:350px">Utilizes the template from the temp storage.</div> |

## Constraints

---
There are no specific constraints for the element.

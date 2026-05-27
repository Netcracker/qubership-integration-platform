# Design Templates

> ⛔️ This functionality is not available via the VS Code Extension.

## Description

---

This page allows to manage templates, that could be utilized as part of chain document generation process. Every template from the table is available for all system users.

## User Interface

---
### View Templates
To view all templates, available in the system, navigate to "**Admin Tools**" and then select "**Design Templates**" tab. This tab contains a table with next columns and elements:

- **Name** - template name.
- **Type** - shows type of the template:
  - **Built-in** - template, that comes with the build. Such template can't be removed.
  - **Custom** - custom template, uploaded manually.
- **Created At - template creation datetime.
- **Control panel** - panel, placed on top of the table. Provides next capabilities:
  - ![20](img/delete.svg)- deletes selected templates.
  - ![20](img/setting.svg)- opens pop-up with table properties that allows to adjust visibility and sequence of columns except **Name**.
  - ![20](img/cloud-download.svg) - exports selected templates in markdown.
  - ![20](img/plus.svg) - initiates new template uploading.

### Add Template
To upload new template, simply click ![20](img/plus.svg) button, available on the control panel of the table on "**Design Templates**" tab, drag and drop template in **.md** format into specialized area and confirm operation. Uploaded template will be presented in the table and be available for selection during document generation for any chain.

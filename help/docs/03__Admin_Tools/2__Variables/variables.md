# Variables (Web UI only)

<div style="border-left: 6px dashed #cc0000; padding: 10px">
This functionality is not available via the VS Code Extension.
</div>
## Description

---
Variables are data items, which can be used in different places of Qubership Integration Platform like chain elements, services, configuration management settings, etc. Variable can contain any useful information for integration flows like namespace, RabbitMQ server address, etc.

There are two variable types:
- **Common** - commonly used type of variables. Their values are stored in the Consul and visible for user.
- **Secured** - variables, specifically derived to handle protected data (e.g. credentials). Such variables are stored in K8S secrets, and their values are hidden on respective tab.

<div style="background-color: #e7f3fe; border-left: 6px solid #2196F3; padding: 10px">
<b>Note:</b><br>
When it is required to refer to the secured variables, which are stored in <b>non-default secret</b>, it is required to specify their secret before their names with a "<b>:</b>" delimiter.
</div>

### Design Time Variables

Variables, that are specified on Admin Tools and referred within a chain via **"\#{"** combination, are considered to be **design time variables**. Hence, when variable's value is updated on Admin Tools ("Variables" section) it won't be automatically picked up during chain execution. To make active chain utilize new design time variable's value, it is required to redeploy the chain or restart engine's pod.

Please refer to the syntax sample below for design time variables:
<pre style="background-color: #F5F5F7"><code  style="color: #000000">#{variable_name}</code><br/><code style="color: #000000">#{secret_name:variable_name} //only for secured variables, stored in non-default secrets.</code></pre>

### Run Time Variables

There is possibility to configure **run time variables**, so the chain will always pick up actual variable's value during the processing. It won't require to re-deploy the chain when variable is configured in the chain following syntaxis, described below:

- For **QIP fields** (where Apache Simple language is being utilized):
<pre style="background-color: #F5F5F7"><code  style="color: #000000">${exchangeProperty.variables["variable_name"]}</code><br/><code style="color: #000000">${exchangeProperty.variables["secret_name:variable_name"]} //only for secured variables, stored in non-default secrets.</code></pre>

- For **Scripts** (that use Groovy language):
<pre style="background-color: #F5F5F7"><code  style="color: #000000">exchange.getProperty("variables").get("variable_name")</code><br/><code style="color: #000000">exchange.getProperty("variables").get("secret_name:variable_name") //only for secured variables, stored in non-default secrets.</code></pre>
### Default Variables

There are currently two default variables, that are being specified as part of the installation:
- namespace
- tenant_id

Both variables will be available on Common variables tab in the application.
<div style="background-color: #e7f3fe; border-left: 6px solid #2196F3; padding: 10px"><b>Note:</b><br>Please note, that both variables will be restored with refreshed values during pod startup. Before operating with mentioned variables, it is strongly recommended to consult with technical team.</div>

### Variables Autocompletion

If variable is configured in Admin Tools, there is no need to remember its exact name when specifying it for a particular field. Simply enter combination of **"#{"** (without quotes) to get a dropdown list with a suggestion and select desired design time variable. When specific fields do not "recognize" such combination, it means that fields do not support operating with them (the only exception is URI field for [HTTP Trigger](docs/01__Chains/1__Graph/1__QIP_Elements_Library/6__Triggers/1__HTTP_Trigger/http_trigger.md) and [HTTP Sender](docs/01__Chains/1__Graph/1__QIP_Elements_Library/7__Senders/4__HTTP_Sender/http_sender.md), please read respective articles for more details).

<div style="background-color: #e7f3fe; border-left: 6px solid #2196F3; padding: 10px">
<b>Note:</b><br>
For <b>common variables used in design time mode</b> there is no need to remember its value or check it on dedicated UI page. <b>Just hover the mouse on variable name and the value will appear on the screen</b>.</div>

## User Interface

---
### View Common Variables

After navigation to "Variables" tab, the table with common variables will be initially showed, where next information and control elements are presented:

- **Key** - non-editable name of the variable. When column is clicked, there is a menu with next options available:
	- **Sort by Ascending** (Default);
	- **Sort by Descending**;
	- **Sort by Default**;
	- **Add Filter**.
- **Value** - editable variable's value. When column is clicked, the same options are available.
- **Control panel** - panel, placed on the right bottom of the table marked with <img src="docs/03__Admin_Tools/2__Variables/img/more.svg" width="20" height="20">. Provides next capabilities:
	- <img src="docs/03__Admin_Tools/2__Variables/img/plus.svg" width="20" height="20"> - allows to add a new variable to the table.
	- <img src="docs/03__Admin_Tools/2__Variables/img/delete.svg" width="20" height="20"> - deletes the variable(s), selected via checkbox.
	- <img src="docs/03__Admin_Tools/2__Variables/img/cloud-download.svg" width="20" height="20"> - exports variables, selected via checkbox. If no specific variables were selected before clicking, then system will export all of them at once.
	- <img src="docs/03__Admin_Tools/2__Variables/img/cloud-upload.svg" width="20" height="20"> - opens pop-up, that allows to import variables.

Under each variable the next actions are available:
* <img src="docs/03__Admin_Tools/2__Variables/img/delete.svg" width="20" height="20"> - Delete the chosen variable.
* <img src="docs/03__Admin_Tools/2__Variables/img/edit.svg" width="20" height="20"> - Change the value of the variable.

### View Secured Variables

Click "**Secured**" sub-tab in the menu on the right to open secured variables table, where next information and control elements are presented:

* **Secret** - name of the secret. Under each secret secured variables can be created.
	- **Key** - non-editable name of the variable or secret. Default secret will also be marked with (Default) identifier. When column is clicked, there is a menu with next options available:
		- **Sort by Ascending** (Default)
		- **Sort by Descending**
		 - **Add Filter**
	- **Value** - editable variable's value, masked with dots.
- **Control panel** - panel, placed on the right bottom of the table marked with <img src="docs/03__Admin_Tools/2__Variables/img/more.svg" width="20" height="20">. Provides next capabilities:
	- <img src="docs/03__Admin_Tools/2__Variables/img/plus.svg" width="20" height="20"> - allows to create a new secret.
	- <img src="docs/03__Admin_Tools/2__Variables/img/delete.svg" width="20" height="20"> - deletes the variable(s), selected via checkbox.

Under each secret the following actions can be applied:
* <img src="docs/03__Admin_Tools/2__Variables/img/cloud-download.svg" width="20" height="20"> - Export secret as Helm Chart.
* <img src="docs/03__Admin_Tools/2__Variables/img/plus.svg" width="20" height="20"> - Add variable.

Under each variable the next actions are available:
* <img src="docs/03__Admin_Tools/2__Variables/img/delete.svg" width="20" height="20"> - Delete the chosen variable.
* <img src="docs/03__Admin_Tools/2__Variables/img/edit.svg" width="20" height="20"> - Change the value of the variable.

### Create Secret

To create a secret, which represents a **secured storage object in Kubernetes**, click "**Add Secret**" button marked with <img src="docs/03__Admin_Tools/2__Variables/img/plus.svg" width="20" height="20"> placed in the action menu <img src="docs/03__Admin_Tools/2__Variables/img/more.svg" width="20" height="20"> on the right bottom, specify the name and confirm operation with "**Create**" button. As the result of this operation, there will be new Secret created with a given name in Kubernetes. Secret's name must be specified in lower case, start with a letter and contain no special symbols besides "-", which could be used as a delimiter.

<div style="background-color: #fff1f0; border-left: 6px solid #ff4538; padding: 10px">
<b>Note:</b><br>
Never <b>ever</b> attempt to override or re-create secret with the name "<i>qip-secured-variables-v2</i>", as it is reserved for <b>default</b> secret name. Any improper actions with default secret may lead to data corruption or system malfunction.
</div>

### Create Variable

To add new variable, click actions menu icon <img src="docs/03__Admin_Tools/2__Variables/img/plus.svg" width="20" height="20"> for respective secret, specify variable name and value in the respective fields. Finally, press button **"Add"** on the widget.

### Edit Variable Value

To edit variable value, hover the mouse over the value field, and click it, when you see icon <img src="docs/03__Admin_Tools/2__Variables/img/edit.svg" width="20" height="20">. Type new value and press **```Enter```**.

### Delete Variable

To delete variable(s), select the suitable variables by checkbox near variable key (name) and press <img src="docs/03__Admin_Tools/2__Variables/img/delete.svg" width="20" height="20">. To delete every variable from the secret, mark the secret with checkbox, click <img src="docs/03__Admin_Tools/2__Variables/img/delete.svg" width="20" height="20"> and confirm operation (secret itself won't be removed).

### Export Variables

To export common variables, select the suitable variable by checkboxes near respective keys and click <img src="docs/03__Admin_Tools/2__Variables/img/cloud-download.svg" width="20" height="20">.

<div style="background-color: #e7f3fe; border-left: 6px solid #2196F3; padding: 10px">
<b>Note:</b><br>
If no specific records are checked for export, all existing variables are going to be exported by pressing the export button.
</div>

### Download Secrets

To download a secret from "**Secured Variables**" tab, click on <img src="docs/03__Admin_Tools/2__Variables/img/cloud-download.svg" width="20" height="20"> for respective secret record. Resulted file will have **.yaml** format.

### Import Variables

To import variables via UI capabilities, press <img src="docs/03__Admin_Tools/2__Variables/img/cloud-upload.svg" width="20" height="20"> button on respective tab for common variables, upload single file in **.yaml** or **.yml** format and press "**Import**". Archive resulted in export chain operation may also be a subject for import if it contains variables in it.

YAML file sample:
<pre style="background-color: #F5F5F7"><code style="color: #000000">KAFKA_URL: "kafka.test-service:0000"</code><br/><code style="color: #000000">MAX_USER_COUNT: "10000"</code><br/><code style="color: #000000">namespace: "Cloud_space"</code><br/><code style="color: #000000">ENV_TYPE: "PROD"</code><br/><code style="color: #000000">1: "1"</code></pre>
If there are Import Instructions configured for common variables that are going to be imported, system **ignores** them during the import process. Find more details about Import Instructions in the respective article: [Import Instructions](docs/03__Admin_Tools/4__Import_Instructions/import_instructions.md).
<div style="background-color: #e7f3fe; border-left: 6px solid #2196F3; padding: 10px"><b>Note:</b><br>
There are syntax specifics, that shall be considered:<ul>
<li>every numeric key shall be wrapped with quotation marks.</li>
<li>both key and value will be imported with AS-IS letter cases.</li></ul></div>
As the result of the import, new variables will be published to the respective storages:

- **Consul** - for common variables.

Variable's values **<font color="red">will override</font>** the ones that are currently available in the storages, if the variables names match.

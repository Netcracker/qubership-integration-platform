# Mapper
## Description

---
Mapper is a fully customized module that provides an ability to setup a mapping between the input/source message and the target one. As part of the mapping, it is also possible to apply transformation logic in order to receive the desired message for its further processing.
<div style="background-color: #FCF3CF; border-left: 6px solid #F7DC6F; padding: 10px"> During the first launch of the chain with Mapper element <b>system performance may be impacted</b> due to resource-intensive data caching. Subsequent requests will perform optimally.</div>

## User Interface

---
### "Mapping" Tab View

Main Mapping Tab that allows to map source message structure to the target one via 3 possible views, accessible via respective sub-tabs:
- "**Graph**" - mapping is performed by connecting attributes with arrows.
- "**Table**" - mapping is performed by utilizing table with attributes.
- "**Text**" - mapping is performed by manually entering rules using specific syntax.

**Graph** and **Table** views contain next set of sections:
- **Constant** - available for Source structure only. Value that will be mapped to target message field(s) as constant (not changing) value.
- **Header** - Camel exchange header from the input message.
- **Property** - Camel exchange property.
- **Body** - body structure of the message.

Variables are also available to use for "**Constants**" and "**Properties**" sections. Please refer to the [[docs/03__Admin_Tools/2__Variables/variables|Variables article]] for more details and syntax samples.

#### Graph View

Default View for Mapper. The window is divided into two parts, where left part represents the source message structure, and the right part represents the target message structure. When attributes are mapped, there will be connection arrows presented between them. To see the tooltip with detailed information about transformation type and transformation description, hover the mouse over connection circle for target attribute.

Structure's sections, mentioned above, are supplied with control buttons:
- <img src="docs/01__Chains/1__Graph/1__QIP_Elements_Library/5__Transformation/2__Mapper/img/plus-circle.svg" width="20" height="20"> - allows to add a new entity (constant, header, property or body parameter). For Headers and Properties, while specifying name, it will be possible to see suggestions with matching names of parameters already existing in the chain. The list of suggestions won't contain nested properties. When data is created from suggestion it will preserve its type and structure. It is also possible to set "Required" flag, while creating new entity under any sections except Constant, to define if the attribute is mandatory.
- <img src="docs/01__Chains/1__Graph/1__QIP_Elements_Library/5__Transformation/2__Mapper/img/close.svg" width="20" height="20"> - allows to fully clear a specific section.
- <img src="docs/01__Chains/1__Graph/1__QIP_Elements_Library/5__Transformation/2__Mapper/img/cloud-upload.svg" width="20" height="20"> - allows to upload or specify a body scheme.
- <img src="docs/01__Chains/1__Graph/1__QIP_Elements_Library/5__Transformation/2__Mapper/img/cloud-download.svg" width="20" height="20"> - allows to download existing body scheme in JSON format.

#### Switch to Table View

This view is accessible by "**Table**" sub-tab and specifically designed to allow working with large schemes via table.

There are next control elements available on the view:
- **Source/Target** switcher - represent type of scheme current table refers to.
- <img src="docs/01__Chains/1__Graph/1__QIP_Elements_Library/5__Transformation/2__Mapper/img/filter.svg" width="20" height="20"> - opens filter pop-up. It's also available by clicking table column names.
- <img src="docs/01__Chains/1__Graph/1__QIP_Elements_Library/5__Transformation/2__Mapper/img/setting.svg" width="20" height="20"> - opens pop-up with table properties that allows to adjust visibility and sequence of columns except **Name**.
- **Search** - search box, provides ability  to find particular parameter(s) by name value, targets/sources value, description value, transformation value.
- <img src="docs/01__Chains/1__Graph/1__QIP_Elements_Library/5__Transformation/2__Mapper/img/more.svg" width="20" height="20"> - allows to choose the following options:
	- <img src="docs/01__Chains/1__Graph/1__QIP_Elements_Library/5__Transformation/2__Mapper/img/file-markdown.svg" width="20" height="20"> - downloads target message structure in markdown.
	- <img src="docs/01__Chains/1__Graph/1__QIP_Elements_Library/5__Transformation/2__Mapper/img/clear.svg" width="20" height="20"> - clears filters.
	- <img src="docs/01__Chains/1__Graph/1__QIP_Elements_Library/5__Transformation/2__Mapper/img/clear.svg" width="20" height="20"> - clears sorters.

Mapper Table contains following columns:
- **Name** - name of the attribute. Free text field.
- **Type** - data type of the attribute. List that contains available data types.
- **Optionality** - shows whether the attribute is mandatory or not. List that contains available values.
- **Description** - hidden by default. Description of the attribute. Free text field.
- **Default Value** - available only when switcher is in "**Source**" position. This value will be applied to the mapped attribute when there is no value received for it in input message. Free text field.
- **Targets** - available only when switcher is in "**Source**" position. List of target attributes. To see the full attribute path move cursor over the specified attribute. Each attribute in this column will be marked with value position label depending on the attribute's position in the target message:
  - B - body
  - H - header
  - P - property
- **Sources** - available only when switcher is in "**Target**" position. List of source attributes. To see the full attribute path move cursor over the specified attribute. Each attribute in this column will be marked with value position label depending on the attribute's position in the source message:
  - B - body
  - C - constants
  - H - header
  - P - property
- **Transformation** - available only when switcher is in "**Target**" position. Allows to specify transformation rules.
- **Transformation Description** - hidden by default. Short description for transformation. Free text field.
- <img src="docs/01__Chains/1__Graph/1__QIP_Elements_Library/5__Transformation/2__Mapper/img/more.svg" width="20" height="20"> - column with control buttons:
  - <img src="docs/01__Chains/1__Graph/1__QIP_Elements_Library/5__Transformation/2__Mapper/img/plus-circle.svg" width="20" height="20"> - allows to add a new entity. Available for main sections and for nested attributes of "object" and "array of objects" types. For Headers and Properties, while specifying name, it will be possible to see suggestions with matching names of parameters already existing in the chain. The list of suggestions won't contain nested properties. When data is created from suggestion it will preserve its type and structure.
  - <img src="docs/01__Chains/1__Graph/1__QIP_Elements_Library/5__Transformation/2__Mapper/img/clear.svg" width="20" height="20"> - allows to fully clear a specific section. Available for main sections and for nested attributes of "object" and "array of objects" types.
  - <img src="docs/01__Chains/1__Graph/1__QIP_Elements_Library/5__Transformation/2__Mapper/img/cloud-upload.svg" width="20" height="20"> - allows to set global message scheme or specific attribute's scheme. Available for main sections and for nested attributes of "object" and "array of objects" types.
  - <img src="docs/01__Chains/1__Graph/1__QIP_Elements_Library/5__Transformation/2__Mapper/img/cloud-download.svg" width="20" height="20"> - allows to download global message scheme or specific attribute's scheme in JSON format. Available for main sections and for nested attributes of "object" and "array of objects" types.
  - <img src="docs/01__Chains/1__Graph/1__QIP_Elements_Library/5__Transformation/2__Mapper/img/delete.svg" width="20" height="20"> - allows to remove an entity. Available for particular attribute only.

#### Switch to Text View

To have a text view of mapping rules for either better visibility and search capabilities or for precise updates, user is able to click on "**Text**" sub-tab on the right bottom part of the Mapper window.
Opened view will have a text box with every mapping rule (that are being applied according to their sequence in the mapping), described with a text. To understand the description, please refer to the format below:
<div><pre style="background-color: #F5F5F7"><code style="color: #000000">[Source parameter] -> [Target parameter] : [transformation rule]</code></pre></div>
<br/>Each parameter will consist from the <b>"position"</b> part and <b>"full path"</b> (delimited by dots).
<div><pre style="background-color: #F5F5F7"><code style="color: #000000">position.path</code></pre></div>
<br/>As an example, header "customer", that is mapped to the body parameter "customerNum", that is part of "customer" object per structure, will be described in a next way:
<div><pre style="background-color: #F5F5F7"><code style="color: #000000">header.customer -> body.customer.customerNum</code></pre></div>
<br/>Next symbols (as well as <i>spaces</i>), when used in parameter's names are going to be escaped by "\"  :
<div><pre style="background-color: #F5F5F7"><code style="color: #000000">.\t\r\n\+-*!><,=%()|&/</code></pre></div>
<br/>Escape sample:
<div><pre style="background-color: #F5F5F7"><code style="color: #000000">"customer.Num"</code><br/><code style="color: #000000">-- is going to be transformed to</code><br/><code style="color: #000000">customer\.Num</code></pre></div>
<br/>Attributes for XML are going to be presented in the structure with the symbol "@":
<div><pre style="background-color: #F5F5F7"><code style="color: #000000">&lt;Body xmlns="http://schemas.xmlsoap.org/soap/envelope/"&gt;</code><br/><code style="color: #000000">-- is going to be transformed to</code><br/><code style="color: #000000">body.@xmlns</code></pre></div><br/>

There are next possible positions supported by Mapper:
- header
- body
- constant
- property

Added rules via "code" mapper editor are fully compatible with UI editor (e.g. added parameters and/or relations are visible in both edit modes).

#### Upload Body Structure

For both **Graph** and **Table** views it is possible to upload the structure to the source or target part, by clicking the <img src="docs/01__Chains/1__Graph/1__QIP_Elements_Library/5__Transformation/2__Mapper/img/cloud-upload.svg" width="20" height="20"> icon under Body section. There are **"Schema"**, **"File"**, **"Metamodel"** and **"Code"** tabs available to be used, depending on the available data.

Supported formats and data:
- JSON data sample
- JSON schema
- XML sample
- XSD file
- Metamodel
- GraphQL schema

<style>
summary {
  display: list-item;
  list-style: disclosure-closed inside;
  cursor: pointer;
}
details[open] > summary {
  list-style: disclosure-open inside;
}
</style>

<div style="background-color: #e7f3fe; border-left: 6px solid #2196F3; padding: 10px">
<b>Note:</b><br>
<ul>
<li>Every input sample or scheme is going to be transformed to the JSON schema as the result. Final message is being built on the basis of resulted scheme.</li>
<li>Mapper supports oneOf, allOf and anyOf schemes, but they shall be properly loaded. Click on expandable section below to see the sample of proper schema</li>
<details><summary><b>JSON schema sample</b></summary>
<div><pre style="background-color: #F5F5F7"><code style="color: #000000">{
  "$id": "https://example.com/person.schema.json",
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "properties": {
	  "composite": { "type": ["string", "number" ] },
	  "one_of_types": {
		  "oneOf": [
			  { "type": "string" },
			  { "type": "number" },
			  {
				  "type": "object",
				  "properties": {
					  "field1": { "type": "string" }
				  }
			  }				  
		  ]
	  },
	  "any_of_types": {
		  "anyOf": [
			  { "type": "string" },
			  { "type": "number" },
			  {
				  "type": "object",
				  "properties": {
					  "field1": { "type": "string" }
				  }
			  }				  
		  ]
	  },
	  "all_of_types": {
		  "allOf": [
			  {
				  "type": "object",
				  "properties": {
					  "field1": { "type": "string" }
				  }
			  },
			  {
				  "type": "object",
				  "properties": {
					  "field2": { "type": "integer" }
				  }
			  }		  
		  ]
	  },
	  "multiple_constraints_as_compound_type": {
		  "oneOf": [
			{ "type": "number", "multipleOf": 5 },
			{ "type": "number", "multipleOf": 3 }
		  ]
	  },
	  "duplicated_object_types": {
		  "oneOf": [
			{
				  "type": "object",
				  "properties": {
					  "field2": { "type": "integer" }
				  }
			},
			{
				  "type": "object",
				  "properties": {
					  "field2": { "type": "integer" }
				  }
			}
		  ]
	  }
  }
}
</code></pre></div></details></ul>
</div>

#### Edit Body Structure
There are control buttons, available under ( <img src="docs/01__Chains/1__Graph/1__QIP_Elements_Library/5__Transformation/2__Mapper/img/more.svg" width="20" height="20">) expandable menu in **Graph View**. This menu becomes visible after hovering the mouse on the desirable attribute. For **Table View** access control buttons with similar functionality exist and they are hidden by default. To display the buttons, hover the mouse on desired attribute or attribute value for its modification.

It is possible to add new body parameter(s) via <img src="docs/01__Chains/1__Graph/1__QIP_Elements_Library/5__Transformation/2__Mapper/img/plus-circle.svg" width="20" height="20"> button and edit exact parameter with <img src="docs/01__Chains/1__Graph/1__QIP_Elements_Library/5__Transformation/2__Mapper/img/edit.svg" width="20" height="20"> button in **Graph View** or to click on the name of the field in **Table View**. In both cases, next attribute's data types are available:
- **Number**
- **String**
- **Boolean**
- **Object**
- **Array of objects/primitives**

It is also possible to upload the structure under existing object or array of objects via <img src="docs/01__Chains/1__Graph/1__QIP_Elements_Library/5__Transformation/2__Mapper/img/cloud-upload.svg" width="20" height="20"> button or download it via <img src="docs/01__Chains/1__Graph/1__QIP_Elements_Library/5__Transformation/2__Mapper/img/cloud-download.svg" width="20" height="20"> button, remove any attribute with <img src="docs/01__Chains/1__Graph/1__QIP_Elements_Library/5__Transformation/2__Mapper/img/delete.svg" width="20" height="20"> button and clean up structure under existing object, array of objects or Body section itself via <img src="docs/01__Chains/1__Graph/1__QIP_Elements_Library/5__Transformation/2__Mapper/img/clear.svg" width="20" height="20"> button.

<div style="background-color: #e7f3fe; border-left: 6px solid #2196F3; padding: 10px">
<b>Note:</b><br>To add <u>attribute</u> for your XML element, please specify "@" prefix. Otherwise, it will be considered as a simple field.
</div>


#### Choose body datatype
After adding at least one parameter to source/target message near the **"Body"** title label with datatype will appear for **Graph** and **Table** views. JSON is selected by default. To change datatype of the message body, click on the label and specify suitable value (**JSON** or **XML**).

<div style="background-color: #e7f3fe; border-left: 6px solid #2196F3; padding: 10px">
<b>Note:</b><br>In case of XML body there is no ability to set <b><i>"schemaLocation"</i></b> parameter value as a namespace configuration. Please, create <i>"schemaLocation"</i> field manually and use <b>"Constants"</b> section with mapping arrow for it.</div>

#### Data Mapping

**Mapping** is a set of rules according to which value from source message should be passed to target message. When working in **Graph View**, mapping is done by dragging the connection circle from source parameter to target one (forming the "connection arrow"). **Table View** requires entering source and target attributes to the table to build proper mapping scheme. When click on the fields in "Sources" or "Targets" columns, system shows pop-up with the list of all selectable attributes and search feature. **Text View** provides ability to map source parameter to the target one with custom syntax, described in "Switch to Text View" section of this article.

Mapper operates with next simple data types (primitives) for messages:
- **String**
- **Number**
- **Boolean**
- **Null**

Mapper operates with next complex data types for message:
- **Object** - object contains primitives, nested objects or arrays of objects (primitives).

<div style="background-color: #e7f3fe; border-left: 6px solid #2196F3; padding: 10px">
<b>Note:</b><br>
Object mapping is a set of primitive mapping operations, there is no ability to map object to object as is.
</div><br/>

- **Array** - array of primitives or objects.

<div style="background-color: #e7f3fe; border-left: 6px solid #2196F3; padding: 10px">
<b>Note:</b><br>
<ul>
<li>Please, use the unified structure in order to map array of objects. Otherwise, all nested fields are going to be excluded from the resulted structure, due to inability to identify the exact object build set.</li>
<li>Mapping between source and target structures where message root is either primitive(s) or array of primitives is not supported.</li>
<li>Source fields of any data type (both simple and complex) containing <b><i>NULL</i> values will be omitted</b> from the target message.</li>
</ul>
</div><br/>

Mapping types:
- **one-to-one** - one value maps to another one
- **one-to-many** - value from one source message field will be mapped to more than one target message fields
- **many-to-one** - values from the several source message fields will be mapped to the one target message field, but transformation shall be applied (read next sections).

<div style="background-color: #e7f3fe; border-left: 6px solid #2196F3; padding: 10px">
<b>Note:</b><br>
Fields in the target message are going to be ordered according to the order they were mapped.
</div>

#### Available Connections and Data Conversion
The table below contains description of possible connection pairs, that could be done via UI and fully supported by service.
<div style="background-color: #e7f3fe; border-left: 6px solid #2196F3; padding: 10px">
<b>Note:</b><br>
<ul><li>Resulted fields are going to be settled, according to the target scheme. For example, if fields are a part of arrays or objects per scheme, then resulted fields will also be placed under the mentioned arrays or objects accordingly.
</li><li>Scenario, when multiple fields are mapped to the single primitive shall be handled with transformation, please find <b>"Expression"</b> transformation type in a following sections.</li>
<li>When scheme for source structure contains default value for a particular field, this value will be also mentioned on the field in the Mapper and be utilized in the mapping logic if:<ul>
<li>Input data does not contain respective field, that has a default value.</li>
<li>Source field, that has a default value is not nested in array and has string, number or boolean type.</li>
</ul></li><li>When one XML object is being mapped to another one, it is required to <b>manually</b> specify namespaces on the target object if required, as they won't be mapped automatically.</li></ul></div>

| Source Field                                              | Target Field                                              | Conversion                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                              |
|-----------------------------------------------------------|-----------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| <div style="width:150px">Primitive(s)</div>               | <div style="width:150px">Primitive</div>                  | <div style="width:790px">**String**:<ul><li>**String** to **String**: No data conversion.</li><li>**String** to **Number**: Convert data if source string contains only digits, otherwise target value will be **null**.</li><li>**String** to **Boolean**: If source value was true the result value will be **true**, in case source string contains only Number value, it will be mapped as usual mapping from Number to Boolean, otherwise **false**.</li></ul>**Number:**<ul><li>**Number** to **String**: Simple conversion from Number to string.</li><li>**Number** to **Number**: No data conversion.</li> <li>**Number** to **Boolean**: If source value equals to 0 target value will be 'false', else 'true'.</li></ul>**Boolean:**<ul><li>**Boolean** to **String**: Simple conversion from Boolean to String.</li><li>**Boolean** to **Number**: If "true" - will be converted to 1, else - 0.</li><li>**Boolean** to **Boolean**: No data conversion.</li></ul></div>                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                    |
| <div style="width:150px">Array of Primitives</div>        | <div style="width:150px">Primitive</div>                  | <div style="width:790px"><ul><li>If <b>single</b> primitive passed in the source array, then simple conversion is going to be applied following <b>"Primitive to Primitive"</b> rules.</li><li>If multiple primitives passed in the source array, then only last primitive's value is going to be applied following <b>"Primitive to Primitive"</b> rules.</li></ul></div>                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                              |
| <div style="width:150px">Array's field (Primitive)</div>  | <div style="width:150px">Primitive</div>                  | <div style="width:790px"><ul><li>If <b>target</b> field <b>is not</b> a part of array, then target field will have the last value from the array. </li><li>If target field <b>is</b> a part of array, then each value from array will be mapped to the new target field. Meaning that there will be as many target fields as many values in the source array.</li></ul></div>                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                           |
| <div style="width:150px">Primitive(s)</div>               | <div style="width:150px">Array of Primitives</div>        | <div style="width:790px">The source value(s) will be converted to Array of primitives. Data type conversion logic is the same as <b>"Primitive to Primitive"</b> case.</div>                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                            |
| <div style="width:150px">Array's field (Primitives)</div> | <div style="width:150px">Array of Primitives</div>        | <div style="width:790px">Each value from array will be placed in the array, following the <b>"Primitive to Primitive"</b> conversion rules. There will be as many target fields as many values in the source array.</div>                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               |
| <div style="width:150px">Array of Primitives</div>        | <div style="width:150px">Array of Primitives</div>        | <div style="width:790px">All values from source array (including the cases when array of primitive is also part of array) will be placed to the target array, following the <b>"Primitive to Primitive"</b> conversion rules.</div>                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                     |
| <div style="width:150px">Object </div>                    | <div style="width:150px">Object</div>                     | <div style="width:790px">Target object will get values for each parameter that have matching name and position in the scheme. <div style="background-color: #e7f3fe; border-left: 6px solid #2196F3; padding: 10px"><b>Notes:</b><br><ul><li>if scheme is defined for both source and target objects, then only matched fields are going to be mapped.</li><li>if scheme is defined for source object but undefined for target, then final object will have source object's structure.</li><li>if scheme is undefined for source object, but defined for target object, then final object will have target object's structure.</li><li>if scheme is undefined both in source and target, then final object will have a structure, built based on the first object in array, received in request.</ul></div></div>                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       |
| <div style="width:150px">Object(s)</div>                  | <div style="width:150px">Array of Objects</div>           | <div style="width:790px">Each source objects will be mapped to array's object, following <b>Object to Object</b> mapping logic.</div>                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                   |
| <div style="width:150px">Array of Objects</div>           | <div style="width:150px">Array of Objects</div>           | <div style="width:790px">Each object from the source array will be mapped to the array's object, following <b>Object to Object</b> mapping logic.</div>                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                 |
| <div style="width:150px">Array of Primitives</div>        | <div style="width:150px">Array's field (Primitives)</div> | <div style="width:790px">Mapper creates as many objects as many values array of primitives contains (including the cases when array of primitive is also part of array) and maps each value of source array to each new object (order of the resulted objects is based on the order of values in the array of primitives).</div>                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        |
| <div style="width:150px">Constant</div>                   | <div style="width:150px">Any type</div>                   | <div style="width:790px">It is possible to add a constant via left (source) scheme and map it to the specific target parameter with specifying target data type. If constant value is not equal to constant name, they both are displayed on **Graph View**, but only value will be mapped. The value is displayed with grey font color right after the constant name. </br>In general, conversion is similar for <b>"Primitive to ..."</b> cases.<br/>User is able to fill next constant's details: <ul><li><b>Value</b> - value of the constant (or it's name, when constant is being generated).</li><li><b>Type</b> - string, number, boolean.</li><li>Checkbox <b>"Generated"</b> - if checked, instead of using constant, data is going to be generated based on the instance's date. Field "Name" is going to be renamed to "Value". "Generated" constants will be marked with label "G" in **Graph** and **Table** views.</li><li><b>Generator</b> - list of all available options for generation: </li><ul><li><b>Current Date</b> - system will fetch current instance's date and pass it to the mapped parameter.</li><li><b>Current Time</b> - system will fetch current instance's time and pass it to the mapped parameter.</li><li><b>Current Date and Time</b> - system will fetch current instance's datetime and pass it to the mapped parameter.</li><li><b>UUID</b> - system generates UUID v4.</li></ul><li>Checkbox <b>Unix epoch</b> - if checked, date is going to be generated in the unix format. Field is not available for UUID.</li><li><b>Format</b> - specifies the format of generated date/datetime dd-mm-yyyy. Field is not available for UUID.</li><li><b>Locale</b> - specifies locale (e.g.,en_US). Field is not available for UUID.</li><li><b>Timezone</b> - specifies time zone (e.g., GMT). Field is not available for UUID.</li></ul><div style="background-color: #e7f3fe; border-left: 6px solid #2196F3; padding: 10px"><b>Note:</b><br>When one "Generated" constant is mapped to many parameters - all parameters will get <u>identical</u> generated value.</div></div> |

#### Apply Transformation
It is possible to apply transformation to mapped attributes on each of Mapper Views:

- **Graph View** - click connection circle for target attribute, when it is connected with one or multiple source attributes with connection arrow(s) and specify transformation settings.
- **Table View** - click on the field in "**Transformation**" field for target attribute, when source attributes are specified in the table, and then define transformation settings. It is possible to add and update transformation description independently of transformation itself in "**Transformation Description**" field for target attribute.
- **Text View** - manually enter transformation setting with custom syntax, described in "Switch to Text View" section of this article.

Please refer to the ["Data Transformation via Mapper"](docs/01__Chains/1__Graph/1__QIP_Elements_Library/5__Transformation/2__Mapper/1__Transformation/transformation.md) article for all details regarding supported transformations.

#### Remove Connection
To remove the connection between source and target field in **Graph View**, it is required to right-click on the arrow to open small dialog window and select "**Delete**" option. To remove multiple connections at once, select them with **Ctrl** button and then choose "**Delete**" option from dialog window, requested via right-click. In **Table View** select one of three variants:
- Simply click delete <img src="docs/01__Chains/1__Graph/1__QIP_Elements_Library/5__Transformation/2__Mapper/img/delete.svg" width="20" height="20"> button for the attribute to fully delete related row.
- Unselect undesired attributes via "**Edit**" pop-up available for values in "**Sources**"/"**Targets**" column.
- Hover the mouse over the field and click <img src="docs/01__Chains/1__Graph/1__QIP_Elements_Library/5__Transformation/2__Mapper/img/close.svg" width="20" height="20">.

In **Text View** remove whole code line, that describes mapping rule in order to remove the connection.

#### Highlight Connection
Left-click any field in **Graph View** to highlight fields and arrows, that are related (connected) to it. When connection arrow is clicked, system will only highlight the arrow itself.

### "Parameters" Tab

#### Advanced Parameters
| Parameter                                                                 | <div style="width:75px">Mandatory</div>   | <div style="width:75px">Data Type</div>  | Description                                                                                                                   | Sample                              |
|---------------------------------------------------------------------------|:------------------------------------------|:-----------------------------------------|-------------------------------------------------------------------------------------------------------------------------------|-------------------------------------|
| <div style="width:150px">Throw exception on transformation failure</div>  | M                                         | Boolean                                  | <div style="width:400px">Checkbox. When checked, throws an exception if transformation fails during chain processing.	</div>  | <div style="width:350px">N/A</div>  |


#### Metadata
| Parameter                                  | <div style="width:75px">Mandatory</div>  | <div style="width:75px">Data Type</div>  | Description                                                              | Sample                                                                                    |
|--------------------------------------------|:-----------------------------------------|:-----------------------------------------|--------------------------------------------------------------------------|-------------------------------------------------------------------------------------------|
| <div style="width:150px">Name</div>        | M                                        | String                                   | <div style="width:400px">Name of the element.	</div>                     | <div style="width:350px">Map to new scheme</div>                                          |
| <div style="width:150px">Description</div> | O                                        | String                                   | <div style="width:400px">Free text field for element description.	</div> | <div style="width:350px">Mapper, which builds a new message scheme from input data.</div> |

### Constraints

---
Please consider next constraints:
- Not every combination of fields and data types is supported by Mapper. When unsupported combination is mapped or transformation settings are invalid, system either highlights transformation icon with red in **Graph View** or highlights with red "**Transformation**" field's frame in **Table View**. For **Table** and **Text** views system additionally shows error indicator. Proper user-friendly tooltip will contain all required details for all views.
- There are specific headers, that are recognized by the system as **context-related** (or **"Technical"**) ones. Such headers will be available in the **"Technical context"** tab under the session, if they were passed in the request. It is only possible to modify **"Technical"** headers in the **sender elements** when option **"Propagate context"** is selected for this element. Please read respective article for each particular sender, where mentioned option is available.
- While working with Properties, Constants and Headers in Mapper's **Text View**, it is not possible to define attribute type during its creation - it is going to be String by default.
- Mapper's **Text View** has no capabilities to add any new attributes to Body structure or nested attributes to other sections of the message.
- Avoid using transformation descriptions while simultaneously working with **Text View**, as these descriptions may be lost after data modification.
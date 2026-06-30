# Supported Transformations

## 1. Concatenation

### Use When

Two or more values must be combined into a single string.

### Syntax

```text
body.field1 + '_' + body.field2
```

### Input Example

```json
{
  "firstName": "John",
  "lastName": "Smith"
}
```

### Output Example

```json
{
  "customerName": "John_Smith"
}
```

### Transformation Definition in mappingDescription

```json
{
  "transformation": {
    "name": "expression",
    "parameters": [
      "body.firstName + '_' + body.lastName"
    ]
  }
}
```

---

## 2. Arithmetic Operations

### Supported Operators

| Operator | Description |
|-----------|------------|
| + | Addition |
| - | Subtraction |
| * | Multiplication |
| / | Division |
| % | Modulo (remainder) |

### Example: Subtraction

Expression:

```text
body.number1 - body.number2
```

#### Input Example:

```json
{
  "number1": 23,
  "number2": 14
}
```

#### Output Example:

```json
{
  "result": 9
}
```
#### Transformation Definition in mappingDescription:

```json
{
  "transformation": {
    "name": "expression",
    "parameters": [
      "body.number1 - body.number2"
    ]
  }
}
```

---

## 3. Conditional Expression (IF)

### Use When

The output depends on a condition.

### Syntax

```text
if(condition, valueIfTrue, valueIfFalse)
```

### Example

#### Expression:

```text
if(body.oldPrice == body.newPrice, true, false)
```

#### Input Example:

```json
{
  "oldPrice": 125,
  "newPrice": 120
}
```

#### Output:

```json
{
  "priceEqual": false
}
```

---

## 4. Conditional Expression with Arithmetic

### Example

Expression:

```text
if(body.price % 2 != 0, 'true', 'false')
```

#### Input Example:

```json
{
  "price": 125
}
```

#### Output Example:

```json
{
  "priceValidation": "true"
}
```
---
## 5. IsEmpty Condition
### Use When
Check that value of source field is empty.
### Syntax
```
isempty(body.customerName)
```
### Input Example
```
{
    "productName": "Mobile Phone"
}
```
### Output Example
```
{  
  "isAnonym": true,
  "product": "Mobile Phone"
}
```
## Transformation Definition in mappingDescription
```
      "transformation" : {
        "name" : "expression",
        "parameters" : [ "isempty(body.customerName)" ]
      }
```
## 6. toLower Expression
### Use When
Convert string value to lowercase.

### Syntax
```
tolower(body.code)
```

### Input Example
```
{
  "code": "AL NT5634BLK"
}
```
### Output Example
```
{  
  "model": "al nt5634blk"
}
```
### Transformation Definition in mappingDescription
```
      "transformation" : {
        "name" : "expression",
        "parameters" : [ "tolower(body.code)" ]
      }
```
## filterBy
### Use when
Pick all objects from the array, where field "value" is bigger than "2" (mentioned fields shall be connected).
### Syntax
```
filterBy(
    body.products,
    body.products.productPrice > constant.2
)
```
### Input Example
```
{
  "products": [
    {
      "productName": "product1",
      "productPrice": 5
    },
    {
      "productName": "product2",
      "productPrice": 1
    },
    {
      "productName": "product3",
      "productPrice": 3
    }
  ]
}
```
### Output Example
```
{
   "newProducts":[
      {
         "productPrice":5,
         "productName":"product1"
      },
      {
         "productPrice":3,
         "productName":"product3"
      }
   ]
}
```
### Transformation Definition in mappingDescription
```
      "transformation" : {
        "name" : "expression",
        "parameters" : [ "filterBy(
           body.products,
           body.products.productPrice > constant.2)" ]
      }
```
```
filterBy ( ${1:source-array}, ${2:filtering-expression} )

Input parameters:
source-array - the source array that will be filtered
filtering-expression - the expression that specifies filtering conditions for objects in the source array

Description:
Picks all objects from the source array that match the filtering-expression

Example:
To pick all objects from the "array_of_objects" source array, where field "value" is bigger than "4" (mentioned fields shall be connected), you can use the following expression:
filterBy(
   body.array_of_objects,
   body.array_of_objects.value > constant.4
)
```
## getFirst
### Use when
Get first object from the array.

### Syntax
```
getFirst(body.customers)
```
### Input Example
```
{
  "customers": [
    {
      "customerName": "John Smith",
        "customerId": 1234567890
    },
    {
        "customerName": "Alan Norman",
        "customerId": 2345678901
    },
    {
        "customerName": "Juan Rodriguez",
        "customerId": 3456789012
    }
  ]
}
```
### Output Example
```

{
   "customer":{
      "customerName":"John Smith",
      "customerId":1234567890
   }
}
```
### Transformation Definition in mappingDescription
```
      "transformation" : {
        "name" : "expression",
        "parameters" : [getFirst(body.customers)]
      }
```

```
getFirst ( ${1:source-array} )

Input parameters:
source-array - the source array from which to get the first element

Description:
Gets the first object from the array.

Example:
To get the first object from the source array of objects "array_of_objects", you can use the following expression:
getFirst(body.array_of_objects)
```
## getFirst - Filter by Value Condition
### Use when
Pick first primitive, which is bigger than "4", from the array.

### Syntax
```
getFirst(
    filterBy(
        body.priceList,
        body.priceList > constant.4
    )
)
```

### Input Example
```
{
  "priceList": [
     3,
     1,
     5,
     7
  ]
}
```

### Output Example
```
{  
    "price": 5
}
```

### Transformation Definition in mappingDescription
```
      "transformation" : {
        "name" : "expression",
        "parameters" : [ 
          getFirst( filterBy(
                     body.priceList,
                     body.priceList > constant.4
                    ) 
          ) 
        ]
      }
```
## getFirst - build map

### Use when
Get first object from array, where id = 100, and return only value(s) placed in "values" (value might be an array of primitives).

### Syntax
```
getFirst(
    map(filterBy(
        body.product.productCharacteristics,
        body.product.productCharacteristics.id == constant.100
    ),
    body.product.productCharacteristics.values
    )
)
```

### Input Example
```
{
  "product": [
    {
      "productCharacteristics": [
        {
          "values": [
            "Prepaid",
            "Postpaid"
          ],
          "id": 200
        },
        {
          "values": [
            "B2C",
            "B2B"
          ],
          "id": 100
        },
        {
          "values": [
            "Preorder",
            "Standalone"
          ],
          "id": 100
        }
      ]
    }
  ]
}
```

### Output Example
```
{  
    "characteristicValues": [
    "B2C",
    "B2B"
  ]
}
```

### Transformation Definition in mappingDescription
```
      "transformation" : {
        "name" : "expression",
        "parameters" : [ ]
      }
```
## trim
### Use when
Trim leading and trailing spaces from the string.

### Syntax
```
trim(body.description)
```

### Input Example
```
{
  "description": "        order123       "
}
```

### Output Example
```
{  
  "updatedDescription": "order123"
}
```

### Transformation Definition in mappingDescription
```
      "transformation" : {
        "name" : "expression",
        "parameters" : [ trim(body.description) ]
      }
```
```
trim ( ${1:source-field} )

Input parameters:
source-field - the source string field where you want to trim leading and trailing spaces

Description:
Trims leading and trailing whitespace from a string.

Example:
To trim leading and trailing whitespace from the source field "my_string_field", you can use the following expression:
trim(body.my_string_field)
```
## replaceAll
### Use when
Put every word and underscores from the string in the round brackets.
### Syntax
```
replaceAll(body.description, '(\w[\w\d_]*)', '($1)')
```

### Input Example
```
{
  "description": "New order_1 has been created"
}
```

### Output Example
```
{  
  "updatedDescription": "(New) (order_1) (has) (been) (created)"
}
```

### Transformation Definition in mappingDescription
```
      "transformation" : {
        "name" : "expression",
        "parameters" : [replaceAll(body.description, '(\w[\w\d_]*)', '($1)')]
      }
```
```
replaceAll ( ${1:source-field}, '${2:pattern}', '${3:replacement}' )
Input parameters:
source-field - source field where the replacement should be applied
pattern - regular expression pattern specifying the part(s) of the source-field to be replaced
replacement - string, specifying the replacement for the parts matching the regular expression in the pattern

Description:
Returns a new string from source-field with all matches of a pattern replaced by a replacement.

Example:
To put every word and underscores from the string in the round brackets, you can use the following expression:
replaceAll(body.string_field, '(\w[\w\d_]*)', '($1)')
```
## map
### Use when
Fetch "id" and "type" for each customer object in array and map them together with a separator (e.g. to an array of strings).
### Syntax
```
map(
    body.customers,
    body.customers.id + '_' + body.customers.type
)
```

### Input Example
```
{
  "customers": [
    {
        "type": "Residential",
        "id": "1234567890"
    },
    {
        "type": "Business",
        "id": "2345678901"
    },
    {
        "type": "Residential",
        "id": "3456789012"
    }
  ]
}
```

### Output Example
```
{
   "customers":[
      "1234567890_Residential",
      "2345678901_Business",
      "3456789012_Residential"
   ]
}
```

### Transformation Definition in mappingDescription
```
      "transformation" : {
        "name" : "expression",
        "parameters" : [
            map(
                body.customers,
                body.customers.id + '_' + body.customers.type
            ) 
        ]    
      }
```
```
map ( ${1:source-array}, ${2:mapping-transformation-expression} )

Input parameters:
source-array - source array for which the mapping should be applied
mapping-transformation-expression - transformation expression that will be applied to each element of the source array

Description:
Maps the source-array to other target field representation, by applying the mapping-transformation-expression.

Example:
To fetch "firstName" field of each customer object from "customers" source array and map it to the concatenation of "SIM Card for " prefix and "firstName" source field, the following expression can be used
```
## sort -ascending
### Use when
Sort array of customers based on each customer's name in alphabet order.
### Syntax
```
sort(body.customers, body.customers.name)
```

### Input Example
```

{
  "customers": [
    {
      "name": "John Smith",
      "age": 32
    },
    {
      "name": "Alan Norman",
      "age": 20
    },
    {
      "name": "Juan Rodriguez",
      "age": 44
    }
  ]
}
```

### Output Example
```

{      
"customerList": [
    {
      "name": "Alan Norman",
      "age": 20
    },
    {
      "name": "John Smith",
      "age": 32
    },
    {
      "name": "Juan Rodriguez",
      "age": 44
    }
  ]
}
```

### Transformation Definition in mappingDescription
```
      "transformation" : {
        "name" : "expression",
        "parameters" : [sort(body.customers, body.customers.name)
 ]
      }
```
```
sort ( ${1:source-array}, ${2:sort-by-field} )

Input parameters:
source-array - original source array that needs to be sorted
sort-by-field - field that will be used to sort the original array

Description:
Sorts input array source-array by the field specified in sort-by-field parameter.

Example:
To sort an array of customers by each customer's name in alphabetical order, you can use the following expression:
sort(body.customers, body.customers.name)
```
## sort - descending
### Use when
Sort values in "numbers" array in descending order.

### Syntax
```
sort(body.numbers, -body.numbers)
```

### Input Example
```

{
  "numbers": [
    12,
    3,
    7,
    33
  ]
}
```

### Output Example
```
{
  "numbers": [
    33,
    12,
    7,
    3
  ]
}
```

### Transformation Definition in mappingDescription
```
      "transformation" : {
        "name" : "expression",
        "parameters" : [ sort(body.numbers, -body.numbers)]
      }
```
## getKeys
### Use when
Pick all field names (keys) from the object.

This function cannot be used inside filterBy, sort, map functions.
### Syntax
```
getKeys(body.customerAddress)
```

### Input Example
```
{
    "customerAddress":{
      "zipCode": "81647",
      "street": {
        "streetName": "Piramide Drive"
      }
    }
}
```

### Output Example
```
{
  "keys": [
    "zipCode",
    "street"
  ]
}
```

### Transformation Definition in mappingDescription
```
      "transformation" : {
        "name" : "expression",
        "parameters" : [getKeys(body.customerAddress)]
      }
```

```
getKeys ( ${1:source-object} )
Input parameters:
source-object - the object to pick field names (keys) from

Description:
Picks all field names (keys) from the object.

Example:
To pick all field names (keys) from the source object "my_object", you can use the following expression:
getKeys(body.my_object)
```
## getValues
### Use when
Pick all field values from the object.

This function cannot be used inside filterBy, sort, map functions.

When getValues is placed inside replaceAll function it will be only properly transformed if fields have string data type
### Syntax
```
getValues(body.contactNumbers)
```

### Input Example
```
{
    "contactNumbers":{
      "personalTelNum": "(***) ***-0886",
      "officeTelNum": "(***) ***-3651",
      "additionalTelNum": "(***) ***-3228"
    }
}
```

### Output Example
```
{
  "contacts": [
    "(***) ***-0886",
    "(***) ***-3651",
    "(***) ***-3228"
  ]
}
```

### Transformation Definition in mappingDescription
```
      "transformation" : {
        "name" : "expression",
        "parameters" : [getValues(body.contactNumbers)]
      }
```

```
getValues ( ${1:source-object} )
Input parameters:
source-object - the object to pick field values from

Description:
Picks all field values from the object.

Example:
To pick all field values from the source object "my_object", you can use the following expression:
getValues(body.my_object)
```
## formatDateTime
### Use when
Build datetime with format 'yyyy-MM-dd' from three connected source body fields.

This function requires at least two arguments specified, one of them must be format.
### Syntax
```
formatDateTime(
    'yyyy-MM-dd',
    constant.year,
    constant.month,
    constant.dayOfMonth
)
```

### Input Example
No body

### Output Example
```
{
  "dateTime": "2024-05-27"
}
```

### Transformation Definition in mappingDescription
```
      "transformation" : {
        "name" : "formatDateTime",
        "parameters" : [ 
            formatDateTime (
                  'yyyy-MM-dd',
                  constant.year,
                  constant.month,
                  constant.dayOfMonth
            )
        ]
      }
```
## formatDateTime - constants
### Use when
Build complex datetime with format 'yyyy-MM-dd HH:mm:ss.SSSZ' from three connected source body fields and constants.

This function requires at least two arguments specified, one of them must be format.
### Syntax
```
formatDateTime(
    'yyyy-MM-dd HH:mm:ss.SSSZ',
    constant.year,
    constant.month,
    constant.dayOfMonth,
    17, -- hours
    11, -- minutes
    12, -- seconds
    532, -- milliseconds
    'Europe/Moscow', -- timezone
    'ru' -- locale
)
```

### Input Example
No body

### Output Example
```
{
  "dateTime": "2024-05-27 17:11:12.532+0300"
}
```

### Transformation Definition in mappingDescription
```
      "transformation" : {
        "name" : "formatDateTime",
        "parameters" : [formatDateTime(...)]
      }
```
## formatDateTime - null
### Use when
Build complex datetime with format 'yyyy-MM-dd HH:mm:ss.SSSZ' but skip hours, minutes, seconds and milliseconds.

This function requires at least two arguments specified, one of them must be format.
### Syntax
```
formatDateTime(
    'yyyy-MM-dd HH:mm:ss.SSSZ',
    constant.year,
    constant.month,
    constant.dayOfMonth,
    null, -- hours
    null, -- minutes
    null, -- seconds
    null, -- milliseconds
    'Europe/Moscow', -- timezone
    'ru' -- locale
)
```

### Input Example
No body

### Output Example
```
{
  "dateTime": "2024-05-27 00:00:00.000+0300"
}
```

### Transformation Definition in mappingDescription
```
      "transformation" : {
        "name" : "formatDateTime",
        "parameters" : [formatDateTime(...)]
      }
```
## makeObject
### Use when
Build key/value map from primitives and objects.

### Syntax
```
makeObject(
    body.customerName, body.orderCount,
    body.product, body.productModel,
    body.deliveryMethod, body.customerAddress
)
```

### Input Example
```
{
   "customerName":"John Smith",
   "orderCount":10,
   "product":"mobile",
   "productModel":"Samsung Galaxy",
   "deliveryMethod":"Store",
   "customerAddress":{
      "zipCode":"81647",
      "street":{
         "streetName":"Piramide Drive"
      }
   }
}
```

### Output Example
```
{
  "customerOrder": {
    "John Smith": 10,
    "mobile": "Samsung Galaxy",
    "Store": {
      "zipCode": "81647",
      "street": {
        "streetName": "Piramide Drive"
      }
    }
  }
}
```

### Transformation Definition in mappingDescription
```
      "transformation" : {
        "name" : "expression",
        "parameters" : [ makeObject(...) ]
      }
```
```
makeObject ( ${1:key}, ${2:value} )
Input parameters:
key - path to source string field, which value will be taken as a new parameter name for resulted object
value - path to source field, object or array, which value will be taken as a new parameter value for resulted object

Description:
Creates new custom object from source objects and primitives based on specified structure.

Example:
To build new object with string key = 'name' and value from "customer" source object, the following expression can be used:
makeObject(
   'name', body.customer
)
```
## mergeObjects
### Use when
Merge objects or array of objects to a new object.

Pay attention to the fields with identical names - the resulted structure will only contain a single field with the value that was populated last
### Syntax
```
mergeObjects(
    body.account,
    body.accountInfo,
    filterBy(body.item, true),  -- every object from array will be fetched
    getFirst(body.contactMethods),
    body.agent,
    makeObject('storeId', '1000008') -- where 'storeId' is a key and '1000008' is a value
)
```

### Input Example
```
{
  "account": {
    "name": "John Smith"
  },
  "accountInfo": {
    "language": "eng"
  },
  "item": [
    {
      "itemName": "Apple IPhone 14"
    },
    {
      "type": "Mobile Phone"
    }
  ],
  "contactMethods": [
    {
      "id": "6e81f49c-ba58-495b-bada-eda6b4d9afd4"
    },
    {
      "id": "916361c5-795c-44a5-a1e8-cad07dbec4b6"
    }
  ],
  "agent": [
    {
      "agentName": "Raul Gonzalez"
    },
    {
      "position": "Manager"
    }
  ]
}
```

### Output Example
```
{
   "order":{
      "name":"John Smith",
      "language":"eng",
      "itemName":"Apple IPhone 14",
      "type":"Mobile Phone",
      "id":"6e81f49c-ba58-495b-bada-eda6b4d9afd4",
      "agentName":"Raul Gonzalez",
      "position":"Manager",
      "storeId":"1000008"
   }
}
```

### Transformation Definition in mappingDescription
```
      "transformation" : {
        "name" : "expression",
        "parameters" : [mergeObjects(...)]
      }
```
```
mergeObjects( ${1:source-object-or-array}, ${2:source-object-or-array})

Input parameters:
source-object-or-array - path to the source object or array of objects to merge into a new object

Description:
Merges several source-object-or-array into new object. It is possible to apply additional functions for source-object-or-array.

Example:
To merge source "customer" object and "orders" array of objects into a new object, the following expression can be used:
mergeObjects(
   body.customer,
   body.orders
)
```
## list
### Use when
Build list from primitives, objects and arrays.

### Syntax
```
list(
    makeObject('requester', body.customerName),
    makeObject('user', property.user.userName)
)
```

### Input Example
```
{
  "customerName": "Stefano Espiga"
}
```

### Output Example
```
{    
    "report": [
    {
      "requester": "Stefano Espiga"
    },
    {
      "user": "system"
    }
  ]
}
```

### Transformation Definition in mappingDescription
```
      "transformation" : {
        "name" : "expression",
        "parameters" : [list(...)]
      }
```

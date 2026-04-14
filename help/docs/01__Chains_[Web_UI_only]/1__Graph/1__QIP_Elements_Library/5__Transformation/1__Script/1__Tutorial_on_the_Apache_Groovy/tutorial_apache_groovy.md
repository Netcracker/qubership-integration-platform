# Tutorial on the Apache Groovy
## Description

---
When it is required to enter a script in the specific QIP element, it must be entered in **Groovy** programming language. By utilizing the syntax of this language, it is possible to access wide scope of instruments, allowing to build mapping, validations, operate with exchange data, etc. This page contains a list of commonly-used scenarios and script examples to fulfill them.

## Application in QIP

---
According to groovy, you can set a suitable kind of scripts, error handling, input/output messages. System displays the code completion popup automatically as user types.

There is a list of modules where you can use Groovy:

1. [Script](../../1__Script/script.md) - the most common module used for creating any scripts of data transformation or something else. For example:
   1. Parsing of incoming messages
   2. Set/Get variable(s) in the Camel Context
   3. Error handling.
2. [Service Call](../../../7__Senders/6__Service_Call/service_call.md) - embedded Scripting module, so the same functionality as for Script.

### Common Cases

#### 1. Library import

```groovy
import groovy.json.JsonSlurper;
import groovy.json.JsonOutput;
```

#### 2. Obtaining the message body from Camel Exchange

```groovy
def requestBody = exchange.getMessage().getBody(String.class);
```

#### 3. Parsing body from Camel Exchange

```groovy
def requestJSON = new groovy.json.JsonSlurper.parseText(exchange.getMessage().getBody(String.class));
```

#### 4. Get field value from JSON body

```groovy
def requestJSON = new groovy.json.JsonSlurper.parseText(exchange.getMessage().getBody(String.class));
def id = requestJSON.id;
```

#### 5. Put some fields to JSON body

```groovy
import groovy.json.JsonSlurper;
import groovy.json.JsonOutput;

def slurper = new JsonSlurper();
def requestJSON = slurper.parseText(exchange.getMessage().getBody(String.class));
requestJSON.put("fieldName", "fieldValue");
```

#### 6. Set property to Camel Exchange

> **ℹ️Note**: Property setting might be useful for storing values, that are necessary for the further chain processing (in another scripting modules, Choice, etc.)

```groovy
exchange.setProperty("propertyName", "propertyValue");
```

#### 7. Get property from Camel Exchange

```groovy
exchange.getProperty("propertyName");
```

#### 8. Function declaration

```groovy
def validateCustomerData(requestJSON){
    def result = false;
    def userData = getUserData(requestJSON);
    result = (userData.firstName != null) && (userData.lastName != null) && (userData.email != null);
    return result;
}
```

#### 9. Build the JSON body

**First option** (more applicable):

```groovy
String variable = "value";
def jsonBody = new groovy.json.JsonBuilder(
    ["primitive": "string",
     "object": [ "objectField": 2 ],
     "objectsArray": [ [ "objectField": 1 ], ["objectField": 2] ],
     "variable": variable,
     "primitivesArray": ["value1", "value2"]]);
```

The result of first option would be:

```json
{
    "primitive": "string",
    "object": {"objectField": 2},
    "objectsArray": [{"objectField": 1}, {"objectField": 2}],
    "variable": "value",
    "primitivesArray": ["value1", "value2"]
}
```

**Second option:**

```groovy
def builder = new groovy.json.JsonBuilder()
def root = builder {
    person {
        firstName 'Guillame'
        lastName 'Laforge'                // Named arguments are valid values for objects too
        address(
            city: 'Paris',
            country: 'France',
            zip: 12345,)
        married true                      // a list of values
        conferences 'JavaOne', 'Gr8conf'
    }
}
def out = new groovy.json.JsonOutput().toJson(root);
```

The result of second option would be:

```json
{
    "person": {
        "firstName": "Guillame",
        "lastName": "Laforge",
        "address": {"city": "Paris", "country": "France", "zip": 12345},
        "married": true,
        "conferences": ["JavaOne", "Gr8conf"]
    }
}
```

#### 10. Configuring response code & body

```groovy
exchange.getMessage().setHeader('CamelHttpResponseCode', '400');
exchange.getMessage().setBody('Invalid data');
```

The result would be having customized message "Invalid data", instead of standard one when code "400" received.

#### 11. Set header and body

```groovy
exchange.getMessage().setHeader("Content-Type", "application/json");
exchange.getMessage().setBody(JsonOutput.toJson("{"id":"1"}"));
exchange.setBody(slurper.parseText(exchange.getProperty("requestQuote", String.class));
```

#### 12. Remove header & body

```groovy
exchange.getMessage().removeHeader('authorization');
exchange.getMessage().removeHeaders("*"); // remove all headers
exchange.getMessage().setBody(null);
```

#### 13. Get path parameters

The same method as for scenario #7:

```groovy
def path_parameter = exchange.getProperty("path_parameter_name");
```

#### 14. Get query parameters

```groovy
def query_parameter = exchange.getMessage().getHeader("query_parameter_name");
```

#### 15. Get variable value

> **ℹ️Note**: More detailed information about secured variables available in [Variables](../../../../../../03__Admin_Tools/2__Variables/variables.md)

```groovy
def somevalue = #{secured_variable_name};
String urlEncodedBody = "username=" + "#{username}".encodeURL();
```

#### 16. Generate UUID

```groovy
def uuid = UUID.randomUUID();
```

#### 17. Get data from technical context

```groovy
import.context.propagation.core.ContextManager;
ContextManager.get(x_request_id);
```

#### 18. Log some information

Log any information:

```groovy
java.util.logging.Logger.getLogger().info("I am a test info log");
```

Log an error:

```groovy
java.util.logging.Logger.getLogger().severe("test error log");
```

#### 19. Get exception from "Try-Catch-Finally" module

Works only for scripts after "Catch".

```groovy
Throwable caused = exchange.getProperty("CamelExceptionCaught", Throwable.class);
```

#### 20. Disable Unicode escaping

> **ℹ️Note**: When working with non-ASCII characters (e.g. hieroglyphs or specific symbols, used in some countries), this might be necessary to disable Unicode escaping to see the original value, instead of its escaped option, like "\u0048\u0065".

```groovy
def generator = new JsonGenerator.Options().disableUnicodeEscaping().build()
exchange.getMessage().setBody(generator.toJson(outputResult))
```

#### 21. Parsing multipart/form-data

```groovy
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import org.apache.tomcat.util.http.fileupload.FileUploadBase;
import org.apache.tomcat.util.http.fileupload.MultipartStream;

byte[] body = exchange.getMessage().getBody(String.class).getBytes();
byte[] boundary = (exchange.getMessage().getHeader(FileUploadBase.CONTENT_TYPE).split("boundary=")[1]).getBytes();

ByteArrayInputStream content = new ByteArrayInputStream(body);
MultipartStream multipartStream = new MultipartStream(content, boundary, MultipartStream.DEFAULT_BUFSIZE, null);

boolean nextPart = multipartStream.skipPreamble();
while (nextPart) {
    String headers = multipartStream.readHeaders();
    ByteArrayOutputStream partContent = new ByteArrayOutputStream();
    multipartStream.readBodyData(partContent);

    // ...

    nextPart = multipartStream.readBoundary();
}
```

## Constraints

---
While configuring **Script**, it is not possible to import classes, defined in another **Script**, as they are isolated from each other on class loader level. Attempting to perform such import will lead to deployment issues.

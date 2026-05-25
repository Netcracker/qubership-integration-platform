# JMS Sender

## Description

---
This element allows messages to be sent to JMS Queue or JMS Topic, depending on the setup.

## User Interface

---
### "Parameters" Tab
| Parameter                                              | Mandatory | Data Type | Description                                                                                                                                                                                                                           | Sample                                                                      |
|--------------------------------------------------------|-----------------------------------------|-----------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------|
| Provider URL            | M                                       | String                                  | Target server path.                                                                                                                                                                                    | "t3://devapp066.qip.com:7154"      |
| Destination             | M                                       | String                                  | Based on the selected Type, it is either identifier of Queue or Topic from JNDI.                                                                                                                       | "jms/BulkQuotationRequest/queue"             |
| Initial context factory | M                                       | String                                  | Address of JNDI lookup service. Object, that builds a context.                                                                                                                                         | "weblogic.jndi.WLInitialContextFactory"      |
| Connection factory      | M                                       | String                                  | JMS object identifier in JNDI factory.                                                                                                                                                                 | "jms/BulkQuotationRequest/connectionfactory" |
| Type                   | M                                       | List                                    | Type of the destination: <ul><li>queue</li> <li>topic</li></ul><br>**Default value**: queue                                                                                                                  | queue                                        |
| Acknowledgement mode    | M                                       | List                                    | The JMS acknowledgement mode. Possible options: <ul><li>AUTO_ACKNOWLEDGE</li><li>CLIENT_ACKNOWLEDGE</li><li>DUPS_OK_ACKNOWLEDGE</li><li>SESSION_TRANSACTED</li></ul><br>**Default value:** AUTO_ACKNOWLEDGE | AUTO_ACKNOWLEDGE                             |
| Message type            | M                                       | List                                    | Type of the message: <ul><li>Bytes</li><li>Map</li><li>Object</li><li>Stream</li><li>Text</li></ul><br>**Default value:** Bytes                                                                              | Bytes                                        |
| password               | O                                       | String                                  | Password to use with the ConnectionFactory. Both username and password can also be configured directly on the ConnectionFactory side.                                                                  | N/A                                          |
| username                | O                                       | String                                  | Username to use with the ConnectionFactory. Both username and password can also be configured directly on the ConnectionFactory side.                                                                  | N/A                                          |
| Propagate context  | M                                       | Boolean                                 | Checkbox, that defines if context to special ("Technical") headers before sending message will be propagated or not.<ul><li>If **checked** (default): context to this call will be propagated, which will lead to the reinstatement of all technical headers, that are stored in context.</li><li>If **unchecked**: call propagation will be switched off, hence values of technical headers, that are stored in the context won't be reinstated.</li></ul>Additionally, when **"Propagate context"** is checked, **"Override Technical Context Headers"** table becomes available to the user. This table allows to override the value for the specific header, that has been propagated from context. <br><br>ℹ️**Note:** For the actual list of technical headers, please, contact system administrator.   | N/A  |
| Name        | M                                        | String                                   | Name of the element.                                        | JMS Sender                                                  |
| Description | O                                        | String                                   | Free text field, that contains description of the element.  | This element allows passing messages to the given JMS topic |

## Constraints

---
There are no specific constraints for the element.

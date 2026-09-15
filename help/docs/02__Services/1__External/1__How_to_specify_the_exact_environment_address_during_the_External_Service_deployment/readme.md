# How to specify the exact Environment address during the external service deployment

## Description

---

During the external service import, there is an option to deploy your service to the exact environment.

Before service is being exported, all environments with address shall be pre-configured, so during the import all of them are part of the import file. During the import, there is no ability to specify the environments that are not part of the import file. Make sure that following steps are done, so the service is deployed on the correct environment:

1. Create External Service or find existing one, your are interested in
2. Go to the Environment tab and create as many environments as you need
3. Add a unique label to each of your environment. It could be done via Editing the environment card ( check for "Labels" combo-box)
4. At this point, you can export the service(s) for future import.

Now, when you are importing external services via [Deployment Process], you can use **deployLabel** parameter in the request's body to specify the exact label (added as part of the preparation steps) of the environment you want to deploy the service to.

API will find the appropriate environment via specified label and deploy the service on it.

## Process Initialization

---

When configured properly, fetching of the environment based on the labels is being done by API.

## User Interface

---

To properly prepare the service for export and make sure that it will be possible to utilize labels during the import, you can use CIP UI. For more details, please refer to [External Services] and [Environments Info].

## Data Storage

---

As part of the preparation steps, labels are going to be stored under the service (technically, in CIP DB).

## Configuration

---

To utilize mentioned approach, configuration shall be done via CIP UI (when preparing the service for export) and via API (when adding the label to the **deployLabel** body parameter).

## API Details

---

Mentioned method utilizes **Import Service** API, described in details in the [Deployment Process] article (see **API Details** section).

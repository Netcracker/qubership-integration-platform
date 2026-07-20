/*
 * Copyright 2024-2025 NetCracker Technology Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.qubership.integration.platform.parsers.model.wsdl;

import org.qubership.integration.platform.parsers.model.ParsedSystemModel;

import java.util.List;

/**
 * What the WSDL parser produces from a specification: the parsed operations and the service
 * endpoints the WSDL declares.
 *
 * <p>Operations feed the catalog's system model; endpoints feed the catalog's environment
 * reconcile. Parsing the source once yields both, so the catalog never re-parses the WSDL.
 *
 * @param systemModel the parsed operations and description, free of any persistence identity
 * @param endpoints the service endpoints the WSDL declares, in document order
 */
public record WsdlParseResult(ParsedSystemModel systemModel, List<WsdlEndpoint> endpoints) {
}

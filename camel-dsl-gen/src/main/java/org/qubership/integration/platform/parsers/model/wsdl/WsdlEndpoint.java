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

/**
 * A service endpoint a WSDL declares: its name and its network address.
 *
 * <p>The parser surfaces these so the catalog wrapper can register the owning system's environments
 * without depending on the WSDL model types. The wrapper decides whether to act on them; the parser
 * neither validates the address nor touches persistence.
 *
 * @param name the endpoint (WSDL 1.1 port or WSDL 2.0 endpoint) name
 * @param address the endpoint address the WSDL declares
 */
public record WsdlEndpoint(String name, String address) {
}

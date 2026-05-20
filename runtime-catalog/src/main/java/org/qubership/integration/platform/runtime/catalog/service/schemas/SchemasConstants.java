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

package org.qubership.integration.platform.runtime.catalog.service.schemas;

import com.fasterxml.jackson.databind.node.TextNode;

public final class SchemasConstants {
    public static final String ARRAY_SCHEMA_CLASS = "ArraySchema";
    public static final String STRING_SCHEMA_CLASS = "StringSchema";
    public static final String UUID_SCHEMA_CLASS = "UUIDSchema";
    public static final String OBJECT_SCHEMA_CLASS = "ObjectSchema";
    public static final String FILE_SCHEMA_CLASS = "FileSchema";
    public static final String DEFAULT_SCHEMA_CLASS = "DefaultSchema";


    public static final String SCHEMA_ID_NODE_NAME = "$id";
    public static final String SCHEMA_HEADER_NODE_NAME = "$schema";
    public static final String TYPE_NODE_NAME = "type";
    public static final String FORMAT_NODE_NAME = "format";
    public static final String ITEMS_NODE_NAME = "items";
    public static final String PROPERTIES_FIELD_NAME = "properties";
    public static final String DEFINITIONS_NODE_NAME = "definitions";
    public static final String REQUIRED = "required";
    public static final TextNode ARRAY_TYPE_NODE = new TextNode("array");
    public static final TextNode OBJECT_TYPE_NODE = new TextNode("object");
    public static final TextNode STRING_TYPE_NODE = new TextNode("string");
    public static final TextNode BINARY_TYPE_NODE = new  TextNode("binary");
    public static final TextNode UUID_TYPE_NODE =  new TextNode("uuid");

    public static final TextNode SCHEMA_HEADER_VALUE = new TextNode("http://json-schema.org/draft-07/schema#");
}

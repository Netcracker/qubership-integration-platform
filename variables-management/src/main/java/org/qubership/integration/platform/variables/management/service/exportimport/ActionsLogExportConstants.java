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

package org.qubership.integration.platform.variables.management.service.exportimport;

public class ActionsLogExportConstants {
    public static final String EXCEL_SHEET_NAME = "Actions log";
    public static final String EXCEL_TIMESTAMP_FORMAT_PATTERN = "yyyy-mm-dd hh:mm:ss AM/PM";
    public static final String ACTION_TIME_HEADER_VALUE = "Action Time";
    public static final String USERNAME_HEADER_VALUE = "Initiator";
    public static final String OPERATION_HEADER_VALUE = "Operation";
    public static final String ENTITY_ID_HEADER_VALUE = "Entity Id";
    public static final String ENTITY_TYPE_HEADER_VALUE = "Entity Type";
    public static final String ENTITY_NAME_HEADER_VALUE = "Entity Name";
    public static final String PARENT_ID_HEADER_VALUE = "Parent Id";
    public static final String PARENT_NAME_HEADER_VALUE = "Parent Name";
    public static final String REQUEST_ID_HEADER_VALUE = "Request Id";

    public static final int TIMESTAMP_CELL_WIDTH = 6250;
    public static final String LEFT = "Left";
    public static final String DATE_PATTERN = "yyyy-MM-dd HH:mm:ss a z";
    public static final String APPLICATION_VERSION = "1.0";
    public static final String APPLICATION_NAME = "Variables Management";
}

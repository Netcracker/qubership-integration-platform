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

package org.qubership.integration.platform.runtime.catalog.service.exportimport;

import lombok.extern.slf4j.Slf4j;
import org.dhatim.fastexcel.Workbook;
import org.dhatim.fastexcel.Worksheet;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog.ActionLog;
import org.qubership.integration.platform.runtime.catalog.service.ActionsLogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.sql.Timestamp;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;

import static org.qubership.integration.platform.runtime.catalog.service.exportimport.ActionsLogExportConstants.*;

@Slf4j
@Service
public class ActionsLogExportService {
    private static final String EXCEL_EXPORT_ERROR = "Failed to create Excel document: ";
    private final ActionsLogService actionsLogService;
    private static final int START_INDEX = 0;
    private static final int ACTION_TIME_INDEX = 0;
    private static final int USERNAME_INDEX = 1;
    private static final int OPERATION_INDEX = 2;
    private static final int ENTITY_ID_INDEX = 3;
    private static final int ENTITY_TYPE_INDEX = 4;
    private static final int ENTITY_NAME_INDEX = 5;
    private static final int PARENT_ID_INDEX = 6;
    private static final int PARENT_NAME_INDEX = 7;
    private static final int REQUEST_ID_INDEX = 8;
    private static final int LAST_INDEX = REQUEST_ID_INDEX;
    private static final int FONT_SIZE = 11;

    @Autowired
    public ActionsLogExportService(ActionsLogService actionsLogService) {
        this.actionsLogService = actionsLogService;
    }

    public byte[] exportAsExcelDocument(Timestamp actionTimeFrom, Timestamp actionTimeTo) {
        try (ByteArrayOutputStream fos = new ByteArrayOutputStream()) {
            Workbook workbook = new Workbook(fos, APPLICATION_NAME, APPLICATION_VERSION);
            Worksheet worksheet = workbook.newWorksheet(EXCEL_SHEET_NAME);
            this.setHeaderCells(worksheet);
            this.setDataCells(worksheet, actionTimeFrom, actionTimeTo);
            workbook.finish();
            return fos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(EXCEL_EXPORT_ERROR + e.getMessage());
        }
    }

    private void setHeaderCells(Worksheet worksheet) {
        worksheet.value(START_INDEX, ACTION_TIME_INDEX, ACTION_TIME_HEADER_VALUE);
        worksheet.value(START_INDEX, USERNAME_INDEX, USERNAME_HEADER_VALUE);
        worksheet.value(START_INDEX, OPERATION_INDEX, OPERATION_HEADER_VALUE);
        worksheet.value(START_INDEX, ENTITY_ID_INDEX, ENTITY_ID_HEADER_VALUE);
        worksheet.value(START_INDEX, ENTITY_TYPE_INDEX, ENTITY_TYPE_HEADER_VALUE);
        worksheet.value(START_INDEX, ENTITY_NAME_INDEX, ENTITY_NAME_HEADER_VALUE);
        worksheet.value(START_INDEX, PARENT_ID_INDEX, PARENT_ID_HEADER_VALUE);
        worksheet.value(START_INDEX, PARENT_NAME_INDEX, PARENT_NAME_HEADER_VALUE);
        worksheet.value(START_INDEX, REQUEST_ID_INDEX, REQUEST_ID_HEADER_VALUE);

        for (int i = START_INDEX; i <= LAST_INDEX; i++) {
            worksheet.style(START_INDEX, i).fontSize(FONT_SIZE).bold().set();
        }
    }

    private void setDataCells(Worksheet worksheet, Timestamp actionTimeFrom, Timestamp actionTimeTo) {

        List<ActionLog> actions = actionsLogService.findAllByActionTimeBetween(actionTimeFrom, actionTimeTo);

        int actionRowNumber = START_INDEX;

        for (ActionLog action : actions) {
            actionRowNumber = actionRowNumber + 1;
            worksheet.value(actionRowNumber, ACTION_TIME_INDEX, applyFormatterOnTimestamp(action.getActionTime()));
            worksheet.style(actionRowNumber, ACTION_TIME_INDEX).horizontalAlignment(LEFT).set();

            worksheet.value(actionRowNumber, USERNAME_INDEX, action.getUser().getUsername());
            worksheet.value(actionRowNumber, OPERATION_INDEX, action.getOperation().name());
            worksheet.value(actionRowNumber, ENTITY_ID_INDEX, action.getEntityId());
            worksheet.value(actionRowNumber, ENTITY_TYPE_INDEX, action.getEntityType().name());
            worksheet.value(actionRowNumber, ENTITY_NAME_INDEX, action.getEntityName());
            worksheet.value(actionRowNumber, PARENT_ID_INDEX, action.getParentId());
            worksheet.value(actionRowNumber, PARENT_NAME_INDEX, action.getParentName());
            worksheet.value(actionRowNumber, REQUEST_ID_INDEX, action.getRequestId());
        }
    }

    private String applyFormatterOnTimestamp(Timestamp actionTime) {
        DateFormat dateFormat = new SimpleDateFormat(DATE_PATTERN);
        dateFormat.setTimeZone(TimeZone.getTimeZone(ZonedDateTime.now().getZone()));
        return dateFormat.format(new Date(actionTime.getTime()));
    }
}

import { api } from "../api/api.ts";
import { LogExportRequestParams } from "../api/apiTypes.ts";
import ExcelJS from "exceljs";
import { downloadFile } from "./download-utils.ts";

const VISIBLE_COLUMNS: string[] = [
  "Action Time",
  "Initiator",
  "Operation",
  "Entity Type",
  "Entity Name",
];

const toTimestamp = (value: unknown): number => {
  if (value instanceof Date) return value.getTime();
  if (typeof value === "string" || typeof value === "number") {
    const ts = new Date(value).getTime();
    return Number.isNaN(ts) ? -Infinity : ts;
  }
  return -Infinity;
};

export async function exportActionsLogAsExcel(dateFrom: Date, dateTo: Date) {
  const requestParams: LogExportRequestParams = {
    actionTimeFrom: dateFrom.getTime(),
    actionTimeTo: dateTo.getTime(),
  };

  const response = await api.exportCatalogActionsLog(requestParams);

  const filename = `audit-${new Date().toISOString()}.xlsx`;
  const mergedRows: Record<string, ExcelJS.CellValue>[] = [];

  const buffer = await response.arrayBuffer();
  const workbook = new ExcelJS.Workbook();
  await workbook.xlsx.load(buffer);

  const sheet = workbook.worksheets[0];
  const headerRow = sheet.getRow(1).values;
  const headers: string[] = Array.isArray(headerRow)
    ? headerRow.slice(1).map((cell: unknown) => String(cell))
    : [];

  sheet.eachRow((row, rowIndex) => {
    if (rowIndex === 1) return;
    const rowData: Record<string, ExcelJS.CellValue> = {};
    row.eachCell((cell, colIndex) => {
      const header = headers[colIndex - 1];
      if (VISIBLE_COLUMNS.includes(header)) {
        rowData[header] = cell.value;
      }
    });
    mergedRows.push(rowData);
  });

  mergedRows.sort(
    (left, right) =>
      toTimestamp(right["Action Time"]) - toTimestamp(left["Action Time"]),
  );

  const outWorkbook = new ExcelJS.Workbook();
  const outSheet = outWorkbook.addWorksheet("Action Log");

  if (mergedRows.length > 0) {
    outSheet.columns = VISIBLE_COLUMNS.map((key) => ({
      header: key,
      key,
      width: 20,
    }));

    mergedRows.forEach((row) => {
      const orderedRow: Record<string, ExcelJS.CellValue> = {};
      VISIBLE_COLUMNS.forEach((key) => {
        orderedRow[key] = row[key] ?? null;
      });
      outSheet.addRow(orderedRow);
    });
  }

  const outBuffer = await outWorkbook.xlsx.writeBuffer();
  const result = new File([outBuffer], filename, {
    type: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
  });

  downloadFile(result);
}

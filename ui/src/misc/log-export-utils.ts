import Cell from "exceljs/index";
import { api } from "../api/api.ts";
import { LogExportRequestParams } from "../api/apiTypes.ts";
import ExcelJS from "exceljs";
import { downloadFile } from "./download-utils.ts";

const exportLogSources = [
  api.exportCatalogActionsLog.bind(api),
  api.exportVariablesManagementActionsLog.bind(api),
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

  const allResponses = await Promise.all(
    exportLogSources.map((actionLogSource) => actionLogSource(requestParams)),
  );

  const filename = `audit-${new Date().toISOString()}.xlsx`;
  const mergedRows: Record<string, Cell.CellValue>[] = [];

  for (const response of allResponses) {
    const buffer = await response.arrayBuffer();
    const workbook = new ExcelJS.Workbook();
    await workbook.xlsx.load(buffer);

    const sheet = workbook.worksheets[0];
    const headerRow = sheet.getRow(1);

    const rawValues = headerRow.values;

    const headers: string[] = Array.isArray(rawValues)
      ? rawValues.slice(1).map((cell: unknown) => String(cell))
      : [];

    sheet.eachRow((row, rowIndex) => {
      if (rowIndex === 1) return;
      const rowData: Record<string, Cell.CellValue> = {};
      row.eachCell((cell, colIndex) => {
        rowData[headers[colIndex - 1]] = cell.value;
      });
      mergedRows.push(rowData);
    });
  }

  mergedRows.sort(
    (left, right) =>
      toTimestamp(right["Action Time"]) - toTimestamp(left["Action Time"]),
  );

  const outWorkbook = new ExcelJS.Workbook();
  const outSheet = outWorkbook.addWorksheet("Action Log");

  if (mergedRows.length > 0) {
    outSheet.columns = Object.keys(mergedRows[0]).map((key) => ({
      header: key,
      key,
      width: 20,
    }));

    mergedRows.forEach((row) => outSheet.addRow(row));
  }

  const outBuffer = await outWorkbook.xlsx.writeBuffer();
  const result = new File([outBuffer], filename, {
    type: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
  });

  downloadFile(result);
}

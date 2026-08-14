import assert from "node:assert/strict";
import { test } from "node:test";
import { createCsv, parseCsv } from "../src/csv.mjs";

test("CSV parser handles BOM, quoted commas, quotes and CRLF", () => {
  const result = parseCsv('\uFEFF圃場ID,名称,メモ\r\nF-1,"北, 1号","引用""あり"\r\n');
  assert.deepEqual(result.headers, ["圃場ID", "名称", "メモ"]);
  assert.deepEqual(result.rows, [{ 圃場ID: "F-1", 名称: "北, 1号", メモ: '引用"あり' }]);
});

test("CSV parser rejects duplicate headers and uneven rows", () => {
  assert.throws(() => parseCsv("id,id\n1,2\n"), /headers/);
  assert.throws(() => parseCsv("id,name\n1\n"), /column count/);
});

test("CSV export quotes cells and neutralizes spreadsheet formulas", () => {
  const csv = createCsv(["name", "memo"], [{ name: "北,1", memo: "=HYPERLINK(\"bad\")" }]);
  assert.equal(csv, '\uFEFFname,memo\r\n"北,1","\'=HYPERLINK(""bad"")"\r\n');
});

const DANGEROUS_CELL = /^[\t\r ]*[=+\-@]/;

export function parseCsv(text, { maxRows = 50000, maxColumns = 200 } = {}) {
  if (typeof text !== "string") throw new TypeError("csv must be text");
  const source = text.replace(/^\uFEFF/, "");
  const records = [];
  let record = [];
  let cell = "";
  let quoted = false;
  for (let index = 0; index <= source.length; index += 1) {
    const character = source[index];
    if (quoted) {
      if (character === '"' && source[index + 1] === '"') { cell += '"'; index += 1; }
      else if (character === '"') quoted = false;
      else if (character === undefined) throw new TypeError("unterminated quoted field");
      else cell += character;
      continue;
    }
    if (character === '"') {
      if (cell) throw new TypeError("quote inside unquoted field");
      quoted = true;
    } else if (character === ",") {
      record.push(cell); cell = "";
    } else if (character === "\n" || character === undefined) {
      record.push(cell); cell = "";
      if (!(record.length === 1 && record[0] === "" && records.length === 0)) records.push(record);
      record = [];
      if (records.length > maxRows + 1) throw new RangeError("too many csv rows");
    } else if (character !== "\r") cell += character;
  }
  if (!records.length) throw new TypeError("empty csv");
  const headers = records[0].map((header) => header.trim());
  if (!headers.length || headers.some((header) => !header) || new Set(headers).size !== headers.length || headers.length > maxColumns) throw new TypeError("invalid csv headers");
  const rows = records.slice(1).filter((values) => values.some((value) => value !== "")).map((values, index) => {
    if (values.length !== headers.length) throw new TypeError(`csv column count at line ${index + 2}`);
    return Object.fromEntries(headers.map((header, column) => [header, values[column]]));
  });
  return { headers, rows };
}

function safeCell(value) {
  const text = value == null ? "" : String(value);
  return DANGEROUS_CELL.test(text) ? `'${text}` : text;
}

function quote(value) {
  const text = safeCell(value);
  return /[",\r\n]/.test(text) ? `"${text.replaceAll('"', '""')}"` : text;
}

export function createCsv(headers, rows) {
  if (!Array.isArray(headers) || !headers.length) throw new TypeError("csv headers are required");
  return `\uFEFF${[headers, ...rows.map((row) => headers.map((header) => row[header]))].map((row) => row.map(quote).join(",")).join("\r\n")}\r\n`;
}

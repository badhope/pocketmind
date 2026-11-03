// 结构化工具结果视图：把 JSON 结果映射成可直接渲染的展示模型。
//
// 为什么存在：formatToolResult 只能处理「顶层全是原始值」的扁平对象；
// kb_search / kb_list / 定时任务列表这类返回 `{ ok, count, results: [...] }`
// 的结果只能甩一段 raw JSON（format.ts:31 的回退分支），手机窄屏上几乎读不了。
// 这里把"列表型结果"识别出来，映射成表格 / 卡片列表两种视图，渲染交给
// components/StructuredResult.tsx；识别不了就返回 null，走原有渲染路径。

/** 已知会返回对象列表的键，按常见程度排序（命中第一个非空数组即采用）。 */
const LIST_KEYS = [
  "results", "items", "jobs", "memories", "sensors", "networks",
  "entries", "matches", "files", "list", "kb", "archives",
];

/** 表格/列表里优先展示的列（其余列按原顺序跟在后面）。 */
const PRIORITY_KEYS = ["title", "name", "id", "content", "chars", "enabled", "status"];

/** 单元格文本上限：超长截断（content 全文很可能 2000 字）。 */
const CELL_MAX = 120;

/** 最多展示的行数：工具结果列表可能很长，视图先给前 20 行。 */
const MAX_ROWS = 20;

export interface KvRow {
  k: string;
  v: string;
}

export interface StructuredItem {
  title: string;
  subtitle?: string;
  rows: KvRow[];
}

export interface StructuredView {
  kind: "table" | "list";
  /** kind=table */
  columns?: string[];
  rows?: Record<string, string>[];
  truncated?: boolean;
  /** kind=list */
  items?: StructuredItem[];
}

function isPlainObject(v: unknown): v is Record<string, unknown> {
  return typeof v === "object" && v !== null && !Array.isArray(v);
}

/** 结果是不是错误形态：错误交给原有红色卡片渲染，不做结构化。 */
function isErrorShape(r: Record<string, unknown>): boolean {
  if (typeof r.exit_code === "number" && r.exit_code !== 0) return true;
  if (typeof r.error === "string" && r.error.trim()) return true;
  if (r.ok === false) return true;
  return false;
}

/** 把一个值变成可展示的单元格文本；对象/数组用紧凑 JSON，超长截断。 */
function cell(v: unknown): string {
  let text: string;
  if (v === null || v === undefined) text = "—";
  else if (typeof v === "boolean") text = v ? "是" : "否";
  else if (typeof v === "string") text = v;
  else if (typeof v === "number") text = String(v);
  else text = JSON.stringify(v);
  return text.length > CELL_MAX ? text.slice(0, CELL_MAX) + "…" : text;
}

/** 字段排序：优先键在前，其余保持原顺序。 */
function orderKeys(keys: string[]): string[] {
  const head = PRIORITY_KEYS.filter((k) => keys.includes(k));
  return [...head, ...keys.filter((k) => !head.includes(k))];
}

/** 从结果对象里找出"非空的 plain-object 数组"。 */
function findList(result: Record<string, unknown>): Record<string, unknown>[] | null {
  if (Array.isArray(result)) {
    return result.every(isPlainObject) && result.length > 0
      ? (result as Record<string, unknown>[])
      : null;
  }
  for (const key of LIST_KEYS) {
    const v = result[key];
    if (Array.isArray(v) && v.length > 0 && v.every(isPlainObject)) {
      return v as Record<string, unknown>[];
    }
  }
  return null;
}

/** 列表条目的标题：第一个可读字段（title/name/…/id）。 */
function itemTitle(row: Record<string, unknown>): string {
  for (const key of ["title", "name", "id", "content"]) {
    const v = row[key];
    if (typeof v === "string" && v.trim()) return v.trim();
    if (typeof v === "number") return String(v);
  }
  return "(条目)";
}

function buildTable(list: Record<string, unknown>[]): StructuredView {
  const rows = list.slice(0, MAX_ROWS);
  const keySet = new Set<string>();
  for (const r of rows) Object.keys(r).forEach((k) => keySet.add(k));
  const columns = orderKeys([...keySet]).slice(0, 5);
  return {
    kind: "table",
    columns,
    rows: rows.map((r) => {
      const o: Record<string, string> = {};
      for (const c of columns) o[c] = cell(r[c]);
      return o;
    }),
    truncated: list.length > MAX_ROWS,
  };
}

function buildList(list: Record<string, unknown>[]): StructuredView {
  const shown = list.slice(0, MAX_ROWS);
  return {
    kind: "list",
    items: shown.map((row) => {
      const title = itemTitle(row);
      const rest = orderKeys(Object.keys(row).filter((k) => row[k] !== title));
      const rows = rest
        .slice(0, 6)
        .map((k) => ({ k, v: cell(row[k]) }))
        .filter((r) => r.v !== "");
      return { title, rows };
    }),
    truncated: list.length > MAX_ROWS,
  };
}

/**
 * 判断一行数据该用表格还是卡片列表：全部单元格都短（≤ 40 字符）且列数
 * ≤ 4 时表格一目了然；否则卡片列表（长文本如 content 摘要在卡片里更好读）。
 */
export function structuredResult(toolName: string, result: unknown): StructuredView | null {
  void toolName; // 目前按结果形状路由；保留参数便于未来按工具特化
  if (Array.isArray(result)) {
    // 顶层就是数组（部分列表型工具的形态）：全部是对象才可结构化
    return result.length > 0 && result.every(isPlainObject)
      ? decideView(result as Record<string, unknown>[])
      : null;
  }
  if (!isPlainObject(result) || isErrorShape(result)) return null;
  const list = findList(result);
  if (!list) return null;
  return decideView(list);
}

/** 列表 → 表格或卡片的形状判定（与"是否可结构化"分离，顶层数组也复用）。 */
function decideView(list: Record<string, unknown>[]): StructuredView {
  const flatCells = list.every((row) =>
    Object.values(row).every((v) => typeof v !== "object" || v === null)
  );
  if (flatCells) {
    const maxLen = Math.max(
      ...list.slice(0, MAX_ROWS).flatMap((r) => Object.values(r).map((v) => cell(v).length))
    );
    const maxCols = Math.max(...list.map((r) => Object.keys(r).length));
    if (maxLen <= 40 && maxCols <= 4 && list.length >= 2) return buildTable(list);
  }
  return buildList(list);
}

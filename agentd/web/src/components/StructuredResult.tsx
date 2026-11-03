import type { StructuredView } from "../lib/structuredResult";

/**
 * 结构化工具结果渲染：table（紧凑表格）/ list（标题卡片）两种视图。
 * 只负责展示 —— 数据模型由 lib/structuredResult.ts 生成；
 * 复制按钮仍复制 formatToolResult 的文本，两种渲染共享同一个动作。
 */
export function StructuredResult({ view }: { view: StructuredView }) {
  if (view.kind === "table" && view.columns && view.rows) {
    return (
      <div className="structured">
        <table className="structured-table">
          <thead>
            <tr>
              {view.columns.map((c) => (
                <th key={c}>{c}</th>
              ))}
            </tr>
          </thead>
          <tbody>
            {view.rows.map((r, i) => (
              <tr key={i}>
                {view.columns!.map((c) => (
                  <td key={c} title={r[c]}>
                    {r[c]}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
        {view.truncated && <p className="structured-note">仅显示前 {view.rows.length} 条</p>}
      </div>
    );
  }
  if (view.kind === "list" && view.items) {
    return (
      <div className="structured">
        <ul className="structured-list">
          {view.items.map((item, i) => (
            <li key={i} className="structured-item">
              <div className="structured-item-title" title={item.title}>
                {item.title}
              </div>
              {item.rows.length > 0 && (
                <dl className="structured-kv">
                  {item.rows.map((r) => (
                    <div key={r.k} className="structured-kv-row">
                      <dt>{r.k}</dt>
                      <dd title={r.v}>{r.v}</dd>
                    </div>
                  ))}
                </dl>
              )}
            </li>
          ))}
        </ul>
        {view.truncated && <p className="structured-note">仅显示前 {view.items.length} 条</p>}
      </div>
    );
  }
  return null;
}

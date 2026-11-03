import { useEffect, useState } from "react";
import type { MessageHit, Session } from "../api";
import { searchMessages } from "../api";
import { relativeTime } from "../lib/format";
import { IconClose, IconPlus, IconTrash } from "./icons";

export function SessionList({
  sessions,
  currentId,
  onClose,
  onPick,
  onNew,
  onDelete,
  onRename,
  onPin,
}: {
  sessions: Session[];
  currentId: string;
  onClose: () => void;
  onPick: (sid: string) => void;
  onNew: () => void;
  onDelete: (sid: string) => void;
  onRename: (sid: string, title: string) => void;
  onPin: (sid: string, pinned: boolean) => void;
}) {
  const [query, setQuery] = useState("");
  const [hits, setHits] = useState<MessageHit[]>([]);
  const [searching, setSearching] = useState(false);
  const q = query.trim().toLowerCase();
  // 标题命中：本地过滤，零延迟
  const filtered = q
    ? sessions.filter((s) => s.title.toLowerCase().includes(q))
    : sessions;

  // 正文命中：防抖后问后端（只有标题搜不到时才值得发请求）
  useEffect(() => {
    const term = query.trim();
    if (term.length < 2) {
      setHits([]);
      return;
    }
    let alive = true;
    setSearching(true);
    const timer = setTimeout(() => {
      searchMessages(term)
        .then((r) => {
          if (alive) setHits(r);
        })
        .catch(() => {
          /* 搜索失败不打扰：标题过滤仍然可用 */
        })
        .finally(() => {
          if (alive) setSearching(false);
        });
    }, 250);
    return () => {
      alive = false;
      clearTimeout(timer);
    };
  }, [query]);

  const rename = (s: Session) => {
    const title = window.prompt("给会话改个名字（留空 = 不改）", s.title);
    if (title && title.trim() && title.trim() !== s.title) onRename(s.id, title.trim());
  };

  const handleDelete = (s: Session) => {
    const ok = window.confirm(`确定删除会话「${s.title}」吗？相关消息与定时任务将一并删除。`);
    if (ok) onDelete(s.id);
  };

  return (
    <div className="overlay" onClick={onClose}>
      <div className="drawer" onClick={(e) => e.stopPropagation()}>
        <div className="drawer-head">
          <h2>会话</h2>
          <div className="drawer-actions">
            <button className="btn primary sm" onClick={onNew}>
              <IconPlus size={13} /> 新建
            </button>
            <button className="icon-btn" onClick={onClose} aria-label="关闭">
              <IconClose />
            </button>
          </div>
        </div>
        <div className="drawer-search">
          <input
            type="search"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="搜索会话标题或聊天内容…"
            aria-label="搜索会话"
          />
        </div>
        <ul className="sessions">
          {filtered.length === 0 && hits.length === 0 && (
            <li className="s-empty">
              {q ? (searching ? "搜索中…" : `没有匹配「${query.trim()}」的会话或消息`) : "还没有会话"}
            </li>
          )}
          {filtered.map((s) => (
            <li
              key={s.id}
              className={`session ${s.id === currentId ? "active" : ""}`}
              onClick={() => onPick(s.id)}
            >
              <div className="session-main">
                <span className="s-title" title="双击重命名" onDoubleClick={() => rename(s)}>
                  {s.pinned ? <span className="s-pin" aria-label="已置顶" title="已置顶">置顶 </span> : null}
                  {s.title}
                </span>
                <span className="s-count">
                  {s.message_count} 条消息
                  {relativeTime(s.updated_at) && (
                    <span className="s-time">{` · ${relativeTime(s.updated_at)}`}</span>
                  )}
                </span>
              </div>
              <div className="s-ops">
                <button
                  className="s-del s-rename"
                  onClick={(e) => {
                    e.stopPropagation();
                    onPin(s.id, !s.pinned);
                  }}
                  title={s.pinned ? "取消置顶" : "置顶会话"}
                  aria-label={s.pinned ? "取消置顶" : "置顶会话"}
                >
                  {s.pinned ? "⇱" : "⇲"}
                </button>
                <button
                  className="s-del s-rename"
                  onClick={(e) => {
                    e.stopPropagation();
                    rename(s);
                  }}
                  title="重命名会话"
                  aria-label="重命名会话"
                >
                  ✎
                </button>
                <button
                  className="s-del"
                  onClick={(e) => {
                    e.stopPropagation();
                    handleDelete(s);
                  }}
                  title="删除会话"
                  aria-label="删除会话"
                >
                  <IconTrash size={15} />
                </button>
              </div>
            </li>
          ))}
        </ul>
        {hits.length > 0 && (
          <div className="hits">
            <div className="hits-head">聊天内容命中 {hits.length} 条</div>
            <ul>
              {hits.map((h) => (
                <li key={h.id} className="hit" onClick={() => onPick(h.session_id)}>
                  <div className="hit-title">
                    {h.matched === "tool_output" && <span className="hit-kind">工具输出</span>}
                    {h.matched === "tool_call" && <span className="hit-kind">工具参数</span>}
                    {h.title}
                  </div>
                  <div className="hit-snippet">{h.snippet}</div>
                </li>
              ))}
            </ul>
          </div>
        )}
      </div>
    </div>
  );
}
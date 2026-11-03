import { describe, expect, it } from "vitest";
import { structuredResult } from "./structuredResult";

describe("structuredResult", () => {
  it("错误形态直接返回 null（保留红色错误卡）", () => {
    expect(structuredResult("kb_search", { error: "知识库不存在" })).toBeNull();
    expect(structuredResult("run_shell", { exit_code: 1, stdout: "" })).toBeNull();
    expect(structuredResult("kb_add", { ok: false })).toBeNull();
  });

  it("扁平键值对象返回 null（由 formatToolResult 的键值渲染处理）", () => {
    expect(structuredResult("get_battery", { level: 80, charging: true })).toBeNull();
    expect(structuredResult("kb_add", { ok: true, id: 3, title: "保修卡" })).toBeNull();
  });

  it("kb_search 的 results 渲为卡片列表（content 预览超长时不适合表格）", () => {
    const view = structuredResult("kb_search", {
      ok: true,
      count: 2,
      results: [
        { id: 1, title: "保修卡", content: "空调 2024 年购买，保修三年，内机编号 2024031188，发票已存知识库备查。" },
        { id: 2, title: "门禁码", content: "A 栋 1234#" },
      ],
    });
    expect(view).not.toBeNull();
    expect(view!.kind).toBe("list");
    expect(view!.items![0].title).toBe("保修卡");
    // title 已作为标题展示，不再重复出现在 kv 行里
    expect(view!.items![0].rows.some((r) => r.k === "title")).toBe(false);
  });

  it("短单元格多行数据渲染为紧凑表格（kb_list / 任务列表形态）", () => {
    const view = structuredResult("kb_list", {
      ok: true,
      count: 3,
      items: [
        { id: 1, title: "保修卡", chars: 120 },
        { id: 2, title: "门禁码", chars: 9 },
        { id: 3, title: "宽带账号", chars: 30 },
      ],
    });
    expect(view).not.toBeNull();
    expect(view!.kind).toBe("table");
    // 可读性优先：title 排在最前，其余列保持原顺序
    expect(view!.columns).toEqual(["title", "id", "chars"]);
    expect(view!.rows![0]).toEqual({ id: "1", title: "保修卡", chars: "120" });
  });

  it("顶层数组也能识别（jobs 列表形态）", () => {
    const view = structuredResult("list_jobs", [
      { id: "j1", name: "低电量提醒", enabled: true },
      { id: "j2", name: "每日天气", enabled: false },
    ]);
    expect(view).not.toBeNull();
    expect(view!.kind).toBe("table");
  });

  it("长文本单行列表用卡片，且内容截断", () => {
    const view = structuredResult("kb_search", {
      ok: true,
      results: [{ id: 1, title: "长文", content: "x".repeat(500) }],
    });
    expect(view!.kind).toBe("list");
    const content = view!.items![0].rows.find((r) => r.k === "content")!;
    expect(content.v.endsWith("…")).toBe(true);
  });

  it("字符串数组等无法结构化的结果返回 null", () => {
    expect(structuredResult("list_sensors", { ok: true, list: ["a", "b"] })).toBeNull();
    expect(structuredResult("kb_search", { ok: true, results: [] })).toBeNull();
  });
});

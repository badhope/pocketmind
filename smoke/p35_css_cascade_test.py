#!/usr/bin/env python3
"""P35 CSS 层叠守卫：通用选择器不得悄悄压掉组件自身的布局规则。

真实缺陷（本轮修掉）：主题选择器 `.theme` 是 `<label>`，而设置面板里有一条
通用的 `.field label { display: block }`。两条规则**权重相同**，靠后那条生效，
于是 `.theme { display: flex; flex-direction: column }` 被覆盖成 `display: block`，
缩略图与文字从"上下排列"塌成"并排重叠"——实测 SVG 占 y=475..505、文字从 y=492
开始，重叠 13px，三个主题选项的文字都被图标压住。

jsdom 不做布局，Vitest 抓不到这类问题；截图巡检能看出来，但那是人工的。
这个脚本把"层叠冲突"本身变成可断言的：解析 styles.css，找出所有
**通用元素选择器 + 组件类选择器** 同权重、且声明了同一属性、且通用规则靠后的
组合，要求组件规则要么权重更高，要么带 !important。

不依赖浏览器、不依赖构建产物。
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except (AttributeError, OSError):
    pass

CSS = Path(__file__).resolve().parent.parent / "agentd" / "web" / "src" / "styles.css"
passed, failed = [], []


def check(name: str, cond: bool, detail: str = ""):
    (passed if cond else failed).append(name)
    print(("  ✅ " if cond else "  ❌ ") + name + (f" — {detail}" if detail and not cond else ""))


def strip_comments(text: str) -> str:
    return re.sub(r"/\*.*?\*/", "", text, flags=re.S)


def parse_rules(css: str):
    """返回 [(selector, {prop: value}, order)]，只处理顶层的普通规则。

    跳过 @media / @keyframes 内部（它们作用域受限，不参与这里的全局层叠判断）。
    """
    css = strip_comments(css)
    rules = []
    order = 0
    depth = 0
    buf = ""
    i = 0
    while i < len(css):
        ch = css[i]
        if ch == "{":
            depth += 1
            if depth == 1:
                selector = buf.strip()
                buf = ""
                # 找到匹配的 }
                j = i + 1
                inner_depth = 1
                body = ""
                while j < len(css) and inner_depth > 0:
                    if css[j] == "{":
                        inner_depth += 1
                    elif css[j] == "}":
                        inner_depth -= 1
                        if inner_depth == 0:
                            break
                    body += css[j]
                    j += 1
                if not selector.startswith("@"):
                    decls = {}
                    for part in body.split(";"):
                        if ":" in part:
                            k, v = part.split(":", 1)
                            k = k.strip()
                            v = v.strip()
                            if k:
                                decls[k] = v
                    if decls:
                        rules.append((selector, decls, order))
                        order += 1
                i = j
                buf = ""
                depth = 0
                continue
            else:
                buf += ch
        elif ch == "}":
            depth = max(0, depth - 1)
            buf = ""
        else:
            if depth == 0:
                buf += ch
        i += 1
    return rules


def specificity(selector: str) -> tuple[int, int, int]:
    """极简特异性计算：id / class+attr+pseudo-class / element+pseudo-element。

    对本项目够用——没有嵌套选择器和 :is()/:where()。
    """
    ids = len(re.findall(r"#[\w-]+", selector))
    classes = len(re.findall(r"\.[\w-]+", selector))
    classes += len(re.findall(r"\[[^\]]+\]", selector))
    classes += len(re.findall(r":(?!:)[\w-]+", selector))
    elements = len(re.findall(r"(?:^|[\s>+~])([a-zA-Z][\w-]*)", selector))
    elements += len(re.findall(r"::[\w-]+", selector))
    return (ids, classes, elements)


def last_compound(selector: str) -> str:
    """取选择器最后一段（决定最终作用元素的那一段）。"""
    return re.split(r"[\s>+~]+", selector.strip())[-1]


LAYOUT_PROPS = {
    "display", "flex-direction", "align-items", "justify-content",
    "grid-template-columns", "gap", "position", "float",
}

# 只关心"元素选择器 vs 组件类"的冲突，例如 `label` vs `.theme`
SRC_DIR = Path(__file__).resolve().parent.parent / "agentd" / "web" / "src"

# 从 TSX 中匹配 <tag ... className="a b c" ...> —— 只取静态字符串字面量里的类名。
# 模板字符串（className={`theme ${x}`}）取其中的固定前缀词，同样有效。
# 注意：不能用 [^>]*? 跨越属性——JSX 里 className 之后常跟 => 箭头函数，
# 那个 > 会让懒惰匹配提前截断，结果一个类都提不到（实测 total classes: 0）。
# 改为「从标签名开始，最多向后看 400 字符找 className」，足以覆盖单行与多行标签。
TAG_CLASS_RE = re.compile(
    # 窗口必须紧贴标签名，否则会跨到下一个元素上：`.drawer-head` 后面紧跟
    # `<h2>会话</h2>` 再才是 `<div className="drawer-actions">`，400 字符窗口
    # 会把 drawer-actions 错记成 h2。用 [^<>]{0,120} 禁止跨越任何标签边界。
    r"<([a-zA-Z][\w-]*)[^<>]{0,120}?className=(?:\"([^\"]*)\"|\{`([^`]*)`\})"
)


def collect_class_tags() -> dict[str, str]:
    """返回 {class_name: tag_name}，用于判断某个类实际作用在什么元素上。"""
    return {k: v[0] for k, v in collect_class_meta().items()}


def collect_class_meta() -> dict[str, tuple[str, str]]:
    """返回 {class_name: (tag_name, file_relpath)}。

    文件路径是必要的：`.plan-tools ul` 与 `.sessions` 虽然一个是 ul、一个也是 ul，
    但它们分别定义在 PlanCard.tsx 和 SessionList.tsx 里，不可能互相嵌套，
    因此不构成层叠冲突。只有同一个文件里出现的组合才可能真的命中同一元素。
    """
    mapping: dict[str, tuple[str, str]] = {}
    for path in SRC_DIR.rglob("*.tsx"):
        if path.name.endswith(".test.tsx"):
            continue
        try:
            text = path.read_text(encoding="utf-8")
        except OSError:
            continue
        for m in TAG_CLASS_RE.finditer(text):
            tag = m.group(1)
            raw = m.group(2) or m.group(3) or ""
            # 模板字符串里去掉 ${...} 表达式，只留静态词
            raw = re.sub(r"\$\{[^}]*\}", " ", raw)
            rel = path.relative_to(SRC_DIR).as_posix()
            for cls in raw.split():
                if re.fullmatch(r"[a-zA-Z][\w-]*", cls):
                    mapping.setdefault(cls, (tag, rel))
    return mapping


def _is_direct_child(src: str, ancestor_class: str, tag: str) -> bool:
    """近似判断：src 里是否存在 `<X className="ancestor">` 直接包住 `<tag ...>`。

    做法：找到每个含 ancestor_class 的开标签，取它之后、缩进不深于它之前，
    是否存在 <tag>。JSX 格式化后子元素缩进更深，这个近似对项目自身的代码成立。
    """
    lines = src.splitlines()
    for i, line in enumerate(lines):
        if f'"{ancestor_class}' not in line and f"{ancestor_class} " not in line:
            continue
        if "className" not in line:
            continue
        base_indent = len(line) - len(line.lstrip())
        for j in range(i + 1, min(i + 40, len(lines))):
            nxt = lines[j]
            if not nxt.strip():
                continue
            indent = len(nxt) - len(nxt.lstrip())
            if indent <= base_indent:
                break
            if re.search(rf"<{re.escape(tag)}", nxt):
                return True
    return False


def main() -> int:
    print("== P35 CSS 层叠守卫（通用规则不得压掉组件布局） ==")
    if not CSS.exists():
        check("styles.css 存在", False, str(CSS))
        return 1
    check("styles.css 存在", True)

    rules = parse_rules(CSS.read_text(encoding="utf-8"))
    check("解析出样式规则", len(rules) > 100, f"rules={len(rules)}")

    # 收集：**以元素名结尾**的选择器声明的布局属性。
    # 关键：`.field label` 也是"通用规则"——它的末段是元素 `label`，会作用到
    # 页面上所有 label。早先只认纯元素选择器（`label {}`），于是 `.field label
    # { display:block }` 被漏掉，守卫在真实缺陷上假绿（变异反证时发现）。
    generic = []
    for sel, decls, order in rules:
        for part in sel.split(","):
            part = part.strip()
            last = last_compound(part)
            if re.fullmatch(r"[a-zA-Z][\w-]*", last):
                for prop in LAYOUT_PROPS:
                    if prop in decls:
                        generic.append((last, prop, decls[prop], order, part))
    check("找到通用元素布局规则", len(generic) > 0, f"n={len(generic)}")

    # 收集：以 .class 结尾的选择器
    component = []
    for sel, decls, order in rules:
        for part in sel.split(","):
            part = part.strip()
            last = last_compound(part)
            if last.startswith(".") and " " not in part:
                for prop in LAYOUT_PROPS:
                    if prop in decls:
                        component.append((last, prop, decls[prop], order, part))

    # 冲突判定要求「两个选择器确实命中同一个元素」。
    # 静态 CSS 分析做不到这一点，所以从 TSX 里提取每个类名对应的真实标签名：
    # `.theme` 来自 <label className="theme">，于是 `.field label { display:block }`
    # 就会命中它 —— 这正是本轮修掉的那个缺陷。反过来 `.app` 是 <div>，
    # 而 `.sheet-head h2` 只命中 h2，两者不可能命中同一元素，不该报警。
    class_meta = collect_class_meta()

    conflicts = []
    for cls_sel, prop, cls_val, cls_order, full_cls in component:
        meta = class_meta.get(cls_sel.lstrip("."))
        if not meta:
            continue  # 找不到真实标签，无法判断，不猜测
        tag, origin_file = meta
        for elem, gprop, gval, g_order, full_gen in generic:
            if gprop != prop:
                continue
            if elem != tag:
                continue  # 通用规则作用在别的标签上，不冲突
            if g_order < cls_order:
                continue  # 通用规则在前，组件规则靠后覆盖它，没问题
            # 通用选择器形如 `.ancestor element`。要构成真实冲突，必须同时满足：
            #   1) 祖先类出现在同一个组件文件里（跨组件不可能嵌套）；
            #   2) 该文件里这个元素的**直接父级**确实是带祖先类的容器。
            # 第 2 步用缩进做近似：JSX 里子元素缩进比父级深。静态分析无法 100% 还原
            # DOM，但这一层足以排除 `.hits ul` 与 `.sessions`（兄弟节点）这类误报，
            # 同时保留 `.field label` 与 `.checkbox-row`（真父子）这类真问题。
            ancestor = None
            m_anc = re.match(r"^\.([\w-]+)\s+", full_gen)
            if m_anc:
                ancestor = m_anc.group(1)
            if ancestor:
                src = (SRC_DIR / origin_file).read_text(encoding="utf-8")
                if f'"{ancestor}' not in src and f"{ancestor} " not in src:
                    continue
                if not _is_direct_child(src, ancestor, tag):
                    continue
            sp_cls = specificity(full_cls)
            sp_gen = specificity(full_gen)
            important = "!important" in cls_val
            if not important and sp_cls <= sp_gen:
                conflicts.append(
                    f"{full_cls}（<{tag}>）的 {prop}={cls_val!r} 被后置的 {full_gen} 的 "
                    f"{gprop}={gval!r} 覆盖（权重 {sp_cls} <= {sp_gen}）"
                )

    check("没有同权重的层叠覆盖", not conflicts, "；".join(conflicts[:3]))

    # ── 回归锁：已知会被 `.field label { display:block }` 压掉的 label 类组件 ──
    # 这些是 <label>，且实际渲染在 .field 容器内。必须显式 !important 才能保住
    # 各自的 flex 布局。两个都已实测确认过（.theme 缩略图与文字重叠 13px；
    # .checkbox-row 的 computed display 是 block 而非 flex）。
    # 上面那套通用冲突检测是启发式的，可能因 DOM 结构变化而漏报；这条是硬断言。
    label_components = {
        ".theme": "主题选择器（缩略图与文字会重叠）",
        ".checkbox-row": "复选框行（勾选框与文字间距失效）",
    }
    field_label_block = any(
        sel.strip() == ".field label" and decls.get("display") == "block"
        for sel, decls, _ in rules
    )
    check(".field label { display:block } 仍然存在（决定后续断言是否必要）",
          field_label_block, "若该规则已删除，下面两条 !important 要求可以放宽")

    for selector, why in label_components.items():
        rule = next((d for s, d, _ in rules if s.strip() == selector), None)
        check(f"{selector} 规则存在", rule is not None)
        if rule is None:
            continue
        display = rule.get("display", "")
        check(
            f"{selector} 用 !important 保住 flex 布局（否则 {why}）",
            "!important" in display and "flex" in display,
            f"display={display!r}",
        )

    print(f"\n结果: {len(passed)} 通过 / {len(failed)} 失败")
    if failed:
        print("失败项：" + "；".join(failed))
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())

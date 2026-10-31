// 手机适配 e2e：把「手机上能不能正常用」变成可重复的断言，而不是靠肉眼看截图。
//
// 覆盖 8 个视口（超窄 / 常见安卓 / 中端机 / Pixel / 键盘弹起矮视口 / 横屏 / 平板 / 桌面），
// 每个视口断言的是一组**布局与触摸不变量**：
//   1. 无横向溢出（内容不撑破屏幕）
//   2. 顶栏不压消息区、输入框与发送键始终在视口内（含键盘弹起后的矮视口）
//   3. 触屏上所有可点元素 >= 36px 高（Android 建议 48dp 的可执行下限）
//   4. 触屏上不依赖 hover 揭示可点元素（此前「复制」按钮 opacity:0，手机上等于不存在）
//   5. 审批卡的 5 个按钮在窄屏下完整可见、不被裁切、高度达标
//   6. 任务清单面板与设置面板不越界、可滚动
//   7. 横向/矮视口下面板不抢占对话区
//
// 运行：cd agentd/web && npx playwright test e2e/mobile.spec.ts
// （webServer 会自动拉起 mock 后端；手机相关演示数据由 mock 的 todo_write 触发词产生）

import { expect, test, type Page } from "@playwright/test";

const VIEWPORTS: Array<{ label: string; width: number; height: number; touch: boolean }> = [
  { label: "超窄 320x568", width: 320, height: 568, touch: true },
  { label: "常见安卓 360x640", width: 360, height: 640, touch: true },
  { label: "中端机 390x844", width: 390, height: 844, touch: true },
  { label: "Pixel 412x915", width: 412, height: 915, touch: true },
  { label: "键盘弹起矮视口 360x360", width: 360, height: 360, touch: true },
  { label: "横屏 844x390", width: 844, height: 390, touch: true },
  { label: "平板 768x1024", width: 768, height: 1024, touch: true },
  { label: "桌面 1280x860", width: 1280, height: 860, touch: false },
];

/**
 * 在「全新会话」里发一条消息。
 *
 * 为什么用 API 建会话 + reload，而不是点抽屉里的「新建」：mock 只在会话**尚无工具结果**时
 * 才发工具调用，所以每条用例都必须落在空会话上；经 UI 走「新建」会受抽屉动画、上一轮流式
 * 收尾、后端偶发抖动等时序影响（曾在「键盘弹起矮视口」用例上误判成审批没出现）。
 * 用 API 建 + 重新加载页面（前端加载 list[0] 即该新会话）是确定性的；
 * 抽屉交互本身仍由下面的显式断言覆盖。
 */
async function freshSessionAndSend(page: Page, message: string): Promise<void> {
  // 等上一轮跑完：busy 时发送键会变成「停止」，此时 Enter 不会提交
  await expect(page.locator(".send.stop")).toHaveCount(0, { timeout: 25000 });
  const res = await page.request.post("/api/sessions", { data: {} });
  expect(res.ok(), "API 建会话失败（后端不可达？）").toBeTruthy();
  await page.reload({ waitUntil: "networkidle" });
  // 连接必须正常：否则「新建没生效 / 消息进了旧会话」会伪装成「审批卡没出现」
  try {
    await expect(page.locator(".conn-banner")).toHaveCount(0, { timeout: 15000 });
  } catch {
    const diag = await page.evaluate(() => ({
      banner: document.querySelector(".conn-banner")?.textContent?.trim(),
      pill: document.querySelector(".topbar-right .pill")?.textContent?.trim(),
      runs: document.querySelectorAll(".tool.waiting").length,
    }));
    const healthStatus = await page
      .request.get("/api/health")
      .then((r) => r.status())
      .catch((e) => `请求失败(${String(e).slice(0, 40)})`);
    throw new Error(`后端连接异常，无法可靠验证 UI：页面=${JSON.stringify(diag)} 直连健康检查=${healthStatus}`);
  }
  await expect(page.locator(".send.stop")).toHaveCount(0, { timeout: 15000 });
  await page.locator(".composer textarea").fill(message);
  await page.keyboard.press("Enter");
}

/** 会话抽屉（手机上的主要导航入口）：手机适配的一部分，独立断言、不影响发送流程。 */
async function expectDrawerUsable(page: Page): Promise<void> {
  await page.click('button[aria-label="会话列表"]');
  await expect(page.locator('input[aria-label="搜索会话"]'), "会话抽屉缺搜索框").toBeVisible();
  await expect(page.getByRole("button", { name: /新建/ }), "会话抽屉缺新建按钮").toBeVisible();
  await page.locator('button[aria-label="关闭"]').first().click();
  await expect(page.locator('input[aria-label="搜索会话"]')).toHaveCount(0, { timeout: 5000 });
}

test.describe("手机适配不变量", () => {
  // 前置：本套件断言的是「危险操作会弹审批卡」，因此必须显式确保权限模式为 approve。
  // 其他套件（m5 / p2）会把 permission_mode 改成 auto，若复用同一实例审批就不再出现——
  // 实测曾因此让 8 个视口全线误报失败。自带前置后，套件对运行环境不再敏感。
  test.beforeAll(async ({ request }) => {
    const res = await request.put("/api/settings", { data: { permission_mode: "approve" } });
    expect(res.ok(), "设置 permission_mode=approve 失败").toBeTruthy();
  });

  for (const vp of VIEWPORTS) {
    test(`${vp.label}：布局与触摸不变量`, async ({ browser }) => {
      const ctx = await browser.newContext({
        viewport: { width: vp.width, height: vp.height },
        isMobile: vp.touch,
        hasTouch: vp.touch,
        locale: "zh-CN",
        colorScheme: "dark",
      });
      const page = await ctx.newPage();
      await page.goto("/", { waitUntil: "networkidle" });
      await page.waitForTimeout(1200);

      // 抽屉可用性（新建 / 搜索）
      await expectDrawerUsable(page);

      // 置顶标记字号：本轮新增的 .s-pin 曾是 11px，只因测试数据里没有置顶会话而逃过
      // 末尾的全局字号断言（假绿）。这里单独置顶一个会话、量完立刻取消：
      // 不能留到用例结束，否则该会话会一直占着列表首位，后续 freshSessionAndSend
      // 新建的会话打不开（init() 取 list[0]），mock 也不会在旧会话里发 tool_call。
      const sessions = await (await page.request.get("/api/sessions")).json();
      const pinnedId = Array.isArray(sessions) && sessions.length ? sessions[0].id : "";
      if (pinnedId) {
        await page.request.put(`/api/sessions/${pinnedId}`, { data: { pinned: true } });
        await page.reload({ waitUntil: "networkidle" });
        await page.locator("textarea").waitFor({ state: "visible", timeout: 15000 });
        await page.click('button[aria-label="会话列表"]');
        const pin = page.locator(".s-pin").first();
        await expect(pin, "置顶标记未渲染").toBeVisible({ timeout: 5000 });
        const pinPx = await pin.evaluate((el) => parseFloat(getComputedStyle(el).fontSize));
        expect(pinPx, `置顶标记字号 ${pinPx}px < 12px`).toBeGreaterThanOrEqual(12);
        await page.locator('button[aria-label="关闭"]').first().click();
        await page.request.put(`/api/sessions/${pinnedId}`, { data: { pinned: false } });
      }

      // 先用 mock 的任务清单触发词产生 todo 面板（同时验证该演示路径可用）
      await freshSessionAndSend(page, "给我列个任务清单");
      await expect(page.locator(".todo-panel")).toBeVisible({ timeout: 15000 });
      await page.waitForTimeout(600);

      const metrics = await page.evaluate(() => {
        const vw = window.innerWidth;
        const vh = window.innerHeight;
        const rect = (sel: string) => {
          const el = document.querySelector(sel);
          if (!el) return null;
          const b = el.getBoundingClientRect();
          return { y: b.y, h: b.height, bottom: b.bottom, right: b.right };
        };
        return {
          vw,
          vh,
          overflow: document.documentElement.scrollWidth - vw,
          topbar: rect(".topbar"),
          msgs: rect(".msgs"),
          chat: rect(".chat"),
          textarea: rect(".composer textarea"),
          send: rect(".send"),
          todo: rect(".todo-panel"),
          composer: rect(".composer"),
          clipped: [...document.querySelectorAll(".pill, .todo-action, .send, .tool-status, .btn")]
            .filter((el) => (el as HTMLElement).offsetParent !== null)
            .filter((el) => el.scrollWidth > el.clientWidth + 1)
            .map((el) => (el.className || "") + ":" + (el.textContent || "").trim().slice(0, 12)),
          smallTargets: [...document.querySelectorAll("button, .session, .task")]
            .filter((el) => (el as HTMLElement).offsetParent !== null)
            .map((el) => ({ c: String(el.className), h: Math.round(el.getBoundingClientRect().height) }))
            .filter((t) => t.h > 0 && t.h < 36),
          msgActionOpacity: getComputedStyle(document.querySelector(".msg-action") || document.body).opacity,
        };
      });

      expect(metrics.overflow, "出现横向溢出").toBeLessThanOrEqual(1);
      // 与滚动容器 .chat 的顶边比较：.msgs 在容器内部，滚动后其视口坐标会变负，
      // 用它判断"顶栏是否压住内容"会随滚动状态误报（此前因自动滚动失效而恒为 0，掩盖了这一点）
      expect(metrics.topbar && metrics.chat ? metrics.topbar.bottom : 0, "顶栏压住消息区").toBeLessThanOrEqual(
        (metrics.chat?.y ?? 0) + 2
      );
      expect(metrics.textarea, "输入框不在视口内").not.toBeNull();
      expect(metrics.textarea!.bottom, "输入框被挤出视口").toBeLessThanOrEqual(metrics.vh + 1);
      expect(metrics.send, "发送键不在视口内").not.toBeNull();
      expect(metrics.send!.bottom, "发送键被挤出视口").toBeLessThanOrEqual(metrics.vh + 1);
      expect(metrics.msgs?.h ?? 0, "消息区可用高度不足").toBeGreaterThanOrEqual(120);
      expect(metrics.clipped, "文本被裁切").toEqual([]);
      // 覆层元素（消息复制按钮 / 工具结果复制按钮）必须完整落在可视容器内：
      // 它们用绝对定位（.msg-action 还是 top:-10px），首条消息很容易被裁掉半个按钮
      const overlays = await page.evaluate(() => {
        const box = document.querySelector(".msgs")!.getBoundingClientRect();
        return [...document.querySelectorAll(".msg-action, .tool-copy")]
          .filter((el) => (el as HTMLElement).offsetParent !== null)
          .map((el) => {
            const b = el.getBoundingClientRect();
            return {
              cls: el.className,
              outTop: b.top < box.top - 1,
              outBottom: b.bottom > box.bottom + 1,
              outLeft: b.left < -1,
              outRight: b.right > window.innerWidth + 1,
            };
          })
          .filter((x) => x.outTop || x.outBottom || x.outLeft || x.outRight);
      });
      expect(overlays, "覆层按钮被裁出容器或屏幕").toEqual([]);
      if (vp.touch) {
        expect(metrics.smallTargets, "触摸目标低于 36px").toEqual([]);
        const pillBox = await page.locator(".pill-btn").first().boundingBox();
        expect(pillBox?.height ?? 0, "状态药丸触摸区低于 44px").toBeGreaterThanOrEqual(44);
        expect(metrics.msgActionOpacity, "触屏上「复制」按钮未常显").toBe("1");
      }
      if (metrics.todo && metrics.composer) {
        expect(metrics.todo.bottom, "任务清单压住输入区").toBeLessThanOrEqual(metrics.composer.y + 1);
      }

      // 审批卡：danger 工具（run_shell）在窄屏下 5 键必须完整可见
      await freshSessionAndSend(page, "运行一个命令");
      await expect(page.locator(".approval")).toBeVisible({ timeout: 20000 });
      const btns = await page.evaluate(() => {
        const vw = window.innerWidth;
        return [...document.querySelectorAll(".approval .btn")].map((el) => {
          const b = el.getBoundingClientRect();
          return {
            t: (el.textContent || "").trim(),
            h: Math.round(b.height),
            right: Math.round(b.right),
            clipped: el.scrollWidth > el.clientWidth + 1,
            vw,
          };
        });
      });
      expect(btns.map((b) => b.t), "审批键缺项或顺序变化").toEqual([
        "允许一次",
        "始终允许",
        "拒绝",
        "全局允许",
        "始终拒绝",
      ]);
      for (const b of btns) {
        expect(b.right, `审批键「${b.t}」超出屏幕`).toBeLessThanOrEqual(b.vw + 1);
        expect(b.clipped, `审批键「${b.t}」文本被裁切`).toBe(false);
        expect(b.h, `审批键「${b.t}」太矮`).toBeGreaterThanOrEqual(vp.touch ? 44 : 36);
      }
      await page.locator(".approval .btn.danger").first().click();
      await page.waitForTimeout(400);

      // 设置面板：可打开、不超宽、内容可滚动
      await page.click('button[aria-label="设置"]');
      await expect(page.locator(".sheet")).toBeVisible({ timeout: 10000 });
      const sheet = await page.evaluate(() => {
        const el = document.querySelector(".sheet")!;
        const b = el.getBoundingClientRect();
        return {
          right: b.right,
          vw: window.innerWidth,
          scrollH: el.scrollHeight,
          clientH: el.clientHeight,
        };
      });
      expect(sheet.right, "设置面板超出屏幕宽度").toBeLessThanOrEqual(sheet.vw + 1);
      expect(sheet.scrollH, "设置面板内容不可滚动").toBeGreaterThanOrEqual(sheet.clientH - 4);

      // 折叠分组：默认只展开少数几组，其余给出"当前值"概览，整页不能是无尽长卷
      // 面板内容是异步加载的，先等分组出现再断言（否则量到的是"加载中…"）
      await expect(page.locator(".group.collapsible").first(), "设置分组未渲染").toBeVisible({ timeout: 10000 });
      const groups = await page.evaluate(() => {
        const els = [...document.querySelectorAll(".group.collapsible")];
        return {
          total: els.length,
          open: els.filter((e) => e.className.includes("open")).length,
          withSummary: els.filter((e) => e.querySelector(".group-summary")).length,
          contentH: document.querySelector(".sheet")!.scrollHeight,
          viewH: document.querySelector(".sheet")!.clientHeight,
          headH: els.map((e) => Math.round(e.querySelector(".group-head-btn")!.getBoundingClientRect().height)),
          // 组头是独立按钮或 div[role=button]（动作按钮并入组头后不能嵌套 <button>，
          // 故可点击性按可访问 role 判定，tagName 不再限定）
          clickable: els.every((e) => {
            const h = e.querySelector(".group-head-btn");
            return h?.tagName === "BUTTON" || h?.getAttribute("role") === "button";
          }),
        };
      });
      expect(groups.total, "设置面板没有可折叠分组").toBeGreaterThanOrEqual(10);
      expect(groups.open, "默认展开的分组过多，等于没折叠").toBeLessThanOrEqual(3);
      expect(groups.withSummary, "收起的分组缺少当前值概览").toBeGreaterThanOrEqual(groups.total - 4);
      // 折叠后内容总量应大幅收敛（改前 5222px）；短视口下按像素衡量，比例会被视口高度放大
      expect(groups.contentH, `设置面板内容 ${groups.contentH}px，仍然过长`).toBeLessThanOrEqual(2600);
      if (groups.viewH >= 600) {
        const pages = Math.round((groups.contentH / groups.viewH) * 10) / 10;
        expect(pages, `设置面板 ${pages} 屏`).toBeLessThanOrEqual(vp.touch ? 3.5 : 4);
      }
      expect(groups.clickable, "分组头不是可点按钮").toBe(true);
      if (vp.touch) {
        expect(groups.headH.filter((h) => h < 44), "分组头触摸高度不足 44px").toEqual([]);
      }

      // 折叠是"先概览再展开"：收起时不建 DOM，展开后内容真的出现
      const chain = page.locator("#cfg-chain-depth");
      await expect(chain, "默认应处于收起状态").toHaveCount(0);
      await page.getByRole("button", { name: /运行参数/ }).click();
      await expect(chain, "展开后内容未出现").toHaveCount(1);
      await page.getByRole("button", { name: /运行参数/ }).click();
      await expect(chain, "再次点击未收起").toHaveCount(0);

      // 工具描述允许换行：原来 nowrap + ellipsis 会把长句直接切掉
      await page.getByRole("button", { name: /工具能力/ }).click();
      await page.waitForTimeout(300);
      const clippedDesc = await page.evaluate(() =>
        [...document.querySelectorAll(".tool-item p")].filter((el) => el.scrollHeight > el.clientHeight + 2).length
      );
      expect(clippedDesc, "工具描述仍被裁切").toBe(0);

      // 文字自动放大：关掉后旋屏/窄栏不会把同一段文字放大到不一致
      const tsa = await page.evaluate(() => {
        const cs = getComputedStyle(document.documentElement);
        return (cs as unknown as { webkitTextSizeAdjust?: string }).webkitTextSizeAdjust
          ?? (cs as unknown as { textSizeAdjust?: string }).textSizeAdjust ?? "";
      });
      expect(tsa, "未关闭系统文字自动放大").toContain("100%");

      await ctx.close();
    });
  }
});

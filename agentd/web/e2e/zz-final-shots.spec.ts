// README 截图生成器（不是行为测试）。
//
// 这些用例把界面渲染成 assets/screenshots/ 里用的图片，跑法：
//   cd agentd/web && npx playwright test e2e/zz-final-shots.spec.ts
//
// 关键约束：必须每条用例都落在**全新会话**上。mock 只在会话尚无工具结果时
// 才发出 tool_call；若沿用上一条用例的会话（或页面默认打开的最近会话里已有
// 工具结果），"运行一个命令"就不会弹审批卡，用例表现为 20s 超时——这是
// 套件顺序耦合，不是 UI 缺陷。因此这里与 mobile.spec.ts 一样用 API 建会话
// 再 reload，不依赖抽屉交互的时序。
import { expect, test, type Page } from "@playwright/test";
import path from "node:path";
import fs from "node:fs";

// 产物路径必须被 .gitignore 覆盖（agentd/web/.gitignore 里有 readme-shots/）：
// 截图由维护者挑图后手工放进 assets/screenshots/，生成物不入库。
const OUT = path.resolve(process.cwd(), "readme-shots");
fs.mkdirSync(OUT, { recursive: true });

async function freshSession(page: Page): Promise<void> {
  await expect(page.locator(".send.stop")).toHaveCount(0, { timeout: 25000 });
  const res = await page.request.post("/api/sessions", { data: {} });
  expect(res.ok(), "API 建会话失败（后端不可达？）").toBeTruthy();
  await page.reload({ waitUntil: "networkidle" });
  await expect(page.locator(".send.stop")).toHaveCount(0, { timeout: 15000 });
}

test.beforeAll(async ({ request }) => {
  const res = await request.put("/api/settings", { data: { permission_mode: "approve" } });
  expect(res.ok(), "设置 permission_mode=approve 失败").toBeTruthy();
});

test("tool-call dark", async ({ page }) => {
  await page.emulateMedia({ colorScheme: "dark" });
  await page.setViewportSize({ width: 1280, height: 800 });
  await page.goto("/", { waitUntil: "networkidle" });
  await page.waitForSelector("textarea");
  await freshSession(page);
  await page.locator("textarea").fill("运行一个命令");
  await page.locator("textarea").press("Enter");
  await page.waitForSelector(".approval", { timeout: 20000 });
  await page.mouse.move(2, 2);
  await page.waitForTimeout(400);
  await page.screenshot({ path: path.join(OUT, "tool-call.png") });
  await page.locator(".approval .btn.danger").first().click();
});

test("settings light", async ({ page }) => {
  await page.emulateMedia({ colorScheme: "light" });
  await page.setViewportSize({ width: 1280, height: 860 });
  await page.goto("/", { waitUntil: "networkidle" });
  await page.waitForSelector("textarea");
  await page.getByRole("button", { name: "设置" }).click();
  await page.waitForTimeout(2500);
  await page.screenshot({ path: path.join(OUT, "settings.png") });
});

test("recipes light", async ({ page }) => {
  await page.emulateMedia({ colorScheme: "light" });
  await page.setViewportSize({ width: 1280, height: 900 });
  await page.goto("/", { waitUntil: "networkidle" });
  await page.waitForSelector("textarea");
  const tasksTab = page.getByRole("tab", { name: "任务" });
  await tasksTab.click();
  await expect(tasksTab).toHaveAttribute("aria-selected", "true");
  await expect(page.getByText(/^定时任务（\d+）$/)).toBeVisible();
  await expect(page.getByText("常用模板", { exact: true })).toBeVisible();
  await expect(page.getByText("低电量提醒")).toBeVisible();
  await page.screenshot({ path: path.join(OUT, "recipes.png") });
});

test("mobile 390", async ({ page }) => {
  await page.emulateMedia({ colorScheme: "light" });
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/", { waitUntil: "networkidle" });
  await page.waitForSelector("textarea");
  await page.waitForTimeout(700);
  await page.screenshot({ path: path.join(OUT, "mobile.png") });
});

test("capability matrix", async ({ page }) => {
  await page.emulateMedia({ colorScheme: "dark" });
  await page.setViewportSize({ width: 1280, height: 1000 });
  await page.goto("/", { waitUntil: "networkidle" });
  await page.waitForSelector("textarea");
  await page.getByRole("button", { name: "设置" }).click();
  await page.waitForTimeout(2500);
  const caps = page.locator(".caps-head");
  if (await caps.count()) {
    await caps.click();
    await page.waitForTimeout(500);
  }
  await page.mouse.move(2, 2);
  await page.screenshot({ path: path.join(OUT, "capabilities.png") });
});

package com.mobi.agent;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.util.Log;

import java.util.List;

/**
 * monitoru2 - 设备控制工具类(纯静态方法)
 *
 * 提供 uiautomator2 风格的设备控制接口,基于无障碍服务实现:
 * - appStart: 启动指定包名的应用
 * - appWait: 等待应用启动完成
 * - u2Click: 在指定坐标点击
 * - u2Input: 输入文本
 * - u2SwipeExt: 滑动操作
 * - appEnd: 强制结束应用进程
 *
 * 注意:本类为纯工具类,不应实例化。
 * 截图功能由 MainActivity + ScreenshotManager + ScreenCaptureService 负责。
 */
public final class monitoru2 {

    private monitoru2() {
        // 工具类,禁止实例化
    }

    /**
     * 检查应用是否已安装
     * @param context 上下文对象
     * @param packageName 应用包名
     * @return 是否已安装
     */
    public static boolean isAppInstalled(Context context, String packageName) {
        try {
            PackageManager packageManager = context.getPackageManager();
            packageManager.getApplicationInfo(packageName, 0);
            Log.d("monitoru2", "应用已安装: " + packageName);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            Log.w("monitoru2", "应用未找到: " + packageName);
            return false;
        } catch (Exception e) {
            Log.e("monitoru2", "检查应用安装状态时出错: " + e.getMessage());
            return false;
        }
    }

    /**
     * 启动指定包名的应用(增强版 - 处理特殊应用)
     * @param context 上下文对象
     * @param packageName 应用包名
     * @throws Exception 启动失败时抛出异常,成功时返回成功信息
     */
    public static void appStart(Context context, String packageName) throws Exception {
        Log.d("monitoru2", "增强版启动应用: " + packageName);

        try {
            // 直接组件启动(模拟 ADB 方式)
            try {
                String activityName = getMainActivityName(packageName);
                if (activityName != null) {
                    Intent intent = new Intent(Intent.ACTION_MAIN);
                    intent.addCategory(Intent.CATEGORY_LAUNCHER);
                    intent.setComponent(new ComponentName(packageName, activityName));
                    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(intent);
                    Log.d("monitoru2", "SUCCESS: 直接组件启动成功 - " + packageName);
                    throw new Exception("SUCCESS: 应用启动成功 - " + packageName);
                }
            } catch (Exception e) {
                if (e.getMessage() != null && e.getMessage().startsWith("SUCCESS:")) {
                    throw e;
                }
                Log.d("monitoru2", "直接组件启动失败: " + e.getMessage());
            }

            // 所有方法都失败
            throw new Exception("❌ 所有启动方式都失败,应用可能不支持外部启动: " + packageName);

        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().startsWith("SUCCESS:")) {
                throw e;
            }

            Log.e("monitoru2", "启动应用失败: " + packageName + ", 错误: " + e.getMessage());
            throw new Exception("启动应用失败: " + e.getMessage());
        }
    }

    /**
     * 获取应用的主Activity名称
     * @param packageName 包名
     * @return Activity名称,失败返回null
     */
    public static String getMainActivityName(String packageName) {
        switch (packageName) {
            case "com.eg.android.AlipayGphone":  // 支付宝
                return "com.eg.android.AlipayGphone.AlipayLogin";
            case "com.tencent.mm":  // 微信
                return "com.tencent.mm.ui.LauncherUI";
            case "com.tencent.mobileqq":  // QQ
                return "com.tencent.mobileqq.activity.SplashActivity";
            case "com.sina.weibo":  // 新浪微博
                return "com.sina.weibo.SplashActivity";
            case "tv.danmaku.bili":  // 哔哩哔哩
                return "tv.danmaku.bili.MainActivityV2";
            case "com.mihoyo.hyperion":  // 米哈游(崩坏:星穹铁道)
                return "com.mihoyo.hyperion.splash.SplashActivity";
            case "com.autonavi.minimap":  // 高德地图
                return "com.autonavi.map.activity.SplashActivity";
            case "me.ele":  // 饿了么
                return "me.ele.Launcher";
            case "com.sankuai.meituan":  // 美团
                return "com.meituan.android.pt.homepage.activity.MainActivity";
            case "com.qiyi.video":  // 爱奇艺
                return "com.qiyi.video.WelcomeActivity";
            case "com.taobao.taobao":  // 淘宝
                return "com.taobao.tao.welcome.Welcome";
            case "com.taobao.trip":  // 飞猪旅行
                return "com.taobao.trip.TripSplashActivity";
            case "ctrip.android.view":  // 携程旅行
                return "ctrip.business.splash.CtripSplashActivity";
            case "com.tongcheng.android":  // 同程旅行
                return "com.tongcheng.android.LoadingActivity";
            case "com.tencent.qqmusic":  // QQ音乐
                return "com.tencent.qqmusic.activity.AppStarterActivity";
            case "com.tencent.qqlive":  // 腾讯视频
                return "com.tencent.qqlive.ona.activity.SplashHomeActivity";
            case "com.xingin.xhs":  // 小红书
                return "com.xingin.xhs.index.v2.IndexActivityV2";
            case "com.zhihu.android":  // 知乎
                return "com.zhihu.android.app.ui.activity.LauncherActivity";
            case "com.jingdong.app.mall":  // 京东
                return "com.jingdong.app.mall.MainFrameActivity";
            case "com.netease.cloudmusic":  // 网易云音乐
                return "com.netease.cloudmusic.activity.IconChangeDefaultAlias";
            case "com.MobileTicket":  // 铁路12306
                return "com.MobileTicket.ui.activity.MainActivity";
            case "com.Qunar":  // 去哪儿
                return "com.mqunar.atom.alexhome.ui.activity.MainActivity";
            case "com.htinns":  // 华住会
                return "com.huazhu.loading.LoadingActivity";
            case "com.alibaba.android.rimet":  // 钉钉
                return "com.alibaba.android.rimet.biz.LaunchHomeActivity";
            case "com.kugou.android": // 酷狗音乐
                return "com.kugou.android.app.splash.SplashActivity";
            case "com.luna.music": // 汽水音乐
                return "com.luna.biz.main.main.MainActivity";
            case "com.baidu.BaiduMap": // 百度地图
                return "com.baidu.baidumaps.MapsActivity";
            case "com.baidu.tieba": // 百度贴吧
                return "com.baidu.tieba.tblauncher.MainTabActivity";
            case "com.phoenix.read": // 红果短剧
                return "com.dragon.read.pages.main.MainFragmentActivity";
            case "com.taobao.idlefish": // 闲鱼
                return "com.taobao.idlefish.maincontainer.activity.MainActivity";
            case "com.xunmeng.pinduoduo": // 拼多多
                return "com.xunmeng.pinduoduo.ui.activity.HomeActivity";
            case "com.dragon.read": // 番茄小说
                return "com.dragon.read.pages.main.MainFragmentActivity";
            case "com.ss.android.ugc.aweme": // 抖音
                return "com.ss.android.ugc.aweme.splash.SplashActivity";
            case "com.tencent.mtt": // qq浏览器
                return "com.tencent.mtt.MainActivity";
            case "com.ss.android.article.news": // 今日头条
                return "com.ss.android.article.news.activity.MainActivity";
            case "com.smile.gifmaker": // 快手
                return "com.yxcorp.gifshow.HomeActivity";
            case "com.ximalaya.ting.android": // 喜马拉雅
                return "com.ximalaya.ting.android.host.activity.MainActivity";
            case "com.youku.phone": // 优酷
                return "com.youku.v2.HomePageEntry";
            case "com.antgroup.leopard.android": // 灵光
                return "com.antgroup.leopard.MainActivity";
            case "com.aliyun.tongyi": // 千问
                return "com.ucpro.BrowserActivity";
            case "com.deepseek.chat": // deepseek
                return "com.deepseek.chat.MainActivity";
            case "com.larus.nova": // 豆包
                return "com.larus.bmhome.chat.ChatActivity";
            case "com.quark.browser": // 夸克
                return "com.ucpro.BrowserActivity";
            case "com.unionpay": // 云闪付
                return "com.unionpay.activity.UPActivityMain";
            case "com.dianping.v1": // 大众点评
                return "com.dianping.v1.NovaMainActivity";
            case "com.baidu.homework": // 作业帮
                return "com.baidu.homework.activity.index.IndexActivity";
            case "com.lemon.lv": // 剪映
                return "com.vega.main.MainActivity";
            case "com.shizhuang.duapp": // 得物
                return "com.shizhuang.duapp.modules.home.ui.HomeActivity";
            case "com.xs.fm": // 番茄畅听
                return "com.dragon.read.pages.main.MainFragmentActivity";
            case "com.wuba.zhuanzhuan": // 转转
                return "com.wuba.zhuanzhuan.activity.MainActivity";
            case "com.ss.android.article.video": // 西瓜视频
                return "com.ss.android.article.video.activity.SplashActivity";
            case "com.baidu.searchbox": // 百度
                return "com.baidu.searchbox.MainActivity";
            default:
                Log.d("monitoru2", "未知应用包名,无法获取主Activity: " + packageName);
                return null;
        }
    }

    /**
     * 等待应用启动完成 - 使用更可靠的检测方法
     * @param context 上下文对象
     * @param packageName 应用包名
     * @param timeout 超时时间(秒)
     * @return 是否启动成功
     */
    public static boolean appWait(Context context, String packageName, int timeout) {
        Log.d("monitoru2", "等待应用启动: " + packageName + ", 超时: " + timeout + "秒");

        long startTime = System.currentTimeMillis();
        long timeoutMs = timeout * 1000;
        int checkCount = 0;

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            try {
                checkCount++;
                Log.d("monitoru2", "第 " + checkCount + " 次检查应用状态: " + packageName);

                // 方法1:检查应用进程是否运行(前台检测)
                android.app.ActivityManager am = (android.app.ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
                List<android.app.ActivityManager.RunningAppProcessInfo> runningProcesses = am.getRunningAppProcesses();

                boolean processRunning = false;
                if (runningProcesses != null) {
                    for (android.app.ActivityManager.RunningAppProcessInfo processInfo : runningProcesses) {
                        if (packageName.equals(processInfo.processName)) {
                            processRunning = true;
                            if (processInfo.importance == android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
                                Log.d("monitoru2", "应用进程在前台运行: " + packageName + " (检查了" + checkCount + "次)");
                                return true;
                            }
                            Log.d("monitoru2", "应用进程在运行但非前台,重要性: " + processInfo.importance);
                        }
                    }
                }

                // 方法2:尝试检查任务列表(可能需要权限)
                try {
                    List<android.app.ActivityManager.RunningTaskInfo> tasks = am.getRunningTasks(1);
                    if (!tasks.isEmpty()) {
                        String topPackage = tasks.get(0).topActivity.getPackageName();
                        Log.d("monitoru2", "当前前台应用: " + topPackage + ", 目标应用: " + packageName);

                        if (packageName.equals(topPackage)) {
                            Log.d("monitoru2", "应用已启动并在前台 (通过任务检查): " + packageName + " (检查了" + checkCount + "次)");
                            return true;
                        }
                    }
                } catch (SecurityException e) {
                    Log.w("monitoru2", "无法获取运行任务列表,权限不足: " + e.getMessage());
                }

                // 如果检查超过20次(10秒)
                if (checkCount >= 20) {
                    if (processRunning) {
                        Log.d("monitoru2", "进程在运行且等待时间够长,假设应用已启动: " + packageName);
                        return true;
                    } else {
                        Log.w("monitoru2", "检查次数已达20次但进程未运行: " + packageName);
                    }
                }

                Thread.sleep(500);

            } catch (Exception e) {
                Log.w("monitoru2", "检查应用状态时出错: " + e.getMessage());
            }
        }

        Log.w("monitoru2", "等待应用启动超时: " + packageName + " (检查了" + checkCount + "次)");
        return false;
    }

    /**
     * 在指定坐标点击
     * @param x X坐标
     * @param y Y坐标
     * @throws Exception 点击失败时抛出异常
     */
    public static void u2Click(int x, int y) throws Exception {
        Log.d("monitoru2", "点击坐标: (" + x + ", " + y + ")");

        try {
            MyAccessibilityService service = MyAccessibilityService.getInstance();
            if (service != null) {
                Log.d("monitoru2", "无障碍服务实例获取成功,准备执行点击");
                boolean success = service.clickOnScreen((float) x, (float) y);
                if (success) {
                    Log.d("monitoru2", "点击成功: (" + x + ", " + y + ")");
                } else {
                    Log.e("monitoru2", "点击失败: 无障碍服务clickOnScreen返回false");
                    throw new Exception("无障碍服务点击失败");
                }
            } else {
                Log.e("monitoru2", "无障碍服务实例为null,服务可能未启用");
                throw new Exception("无障碍服务未启用");
            }
        } catch (Exception e) {
            Log.e("monitoru2", "点击操作异常: " + e.getMessage());
            throw new Exception("点击失败: " + e.getMessage());
        }
    }

    /**
     * 输入文本
     * @param text 要输入的文本
     * @throws Exception 输入失败时抛出异常
     */
    public static void u2Input(String text) throws Exception {
        Log.d("monitoru2", "输入文本: " + text);

        try {
            MyAccessibilityService service = MyAccessibilityService.getInstance();
            if (service != null) {
                Log.d("monitoru2", "无障碍服务实例获取成功,准备执行输入操作");

                // 首先尝试传统的无障碍服务输入(避免系统剪贴板Toast)
                Log.d("monitoru2", "尝试使用传统方法输入文本");
                long startTime = System.currentTimeMillis();
                boolean success = service.inputTextSafely(text);
                long endTime = System.currentTimeMillis();
                long duration = endTime - startTime;

                Log.d("monitoru2", "inputTextSafely执行完成,耗时: " + duration + "ms,结果: " + success);

                if (success) {
                    Log.d("monitoru2", "传统方法文本输入成功: " + text);
                    return;
                } else {
                    Log.w("monitoru2", "传统方法失败,尝试剪贴板方法");
                }

                // 剪贴板粘贴方式(备用)
                Log.d("monitoru2", "尝试使用剪贴板粘贴方式输入文本");
                success = service.pasteTextFromClipboard(text);
                if (success) {
                    Log.d("monitoru2", "剪贴板粘贴输入成功: " + text);
                } else {
                    Log.e("monitoru2", "所有输入方法都失败了");
                    throw new Exception("文本输入失败");
                }
            } else {
                Log.e("monitoru2", "无障碍服务实例为null,服务可能未启用");
                throw new Exception("无障碍服务未启用");
            }
        } catch (Exception e) {
            Log.e("monitoru2", "输入操作异常: " + e.getMessage());
            throw new Exception("输入失败: " + e.getMessage());
        }

        Log.d("monitoru2", "u2Input方法执行完成");
    }

    /**
     * 滑动操作
     * @param direction 方向(up/down/left/right)
     * @param scale 滑动比例(0.0-1.0)
     * @throws Exception 滑动失败时抛出异常
     */
    public static void u2SwipeExt(String direction, double scale) throws Exception {
        Log.d("monitoru2", "滑动操作: " + direction + ", 比例: " + scale);

        try {
            MyAccessibilityService service = MyAccessibilityService.getInstance();
            if (service != null) {
                Log.d("monitoru2", "无障碍服务实例获取成功,准备执行滑动操作");
                boolean success = false;

                switch (direction.toLowerCase()) {
                    case "up":
                        success = service.swipeUp();
                        break;
                    case "down":
                        success = service.swipeDown();
                        break;
                    case "left":
                        success = service.swipeLeft();
                        break;
                    case "right":
                        success = service.swipeRight();
                        break;
                    default:
                        Log.e("monitoru2", "不支持的滑动方向: " + direction);
                        throw new Exception("不支持的滑动方向: " + direction);
                }

                if (success) {
                    Log.d("monitoru2", "滑动成功: " + direction);
                } else {
                    Log.e("monitoru2", "滑动失败: 无障碍服务返回false");
                    throw new Exception("无障碍服务滑动失败");
                }
            } else {
                Log.e("monitoru2", "无障碍服务实例为null,服务可能未启用");
                throw new Exception("无障碍服务未启用");
            }
        } catch (Exception e) {
            Log.e("monitoru2", "滑动操作异常: " + e.getMessage());
            throw new Exception("滑动失败: " + e.getMessage());
        }
    }

    /**
     * 检查指定应用是否正在运行
     * @param context 上下文对象
     * @param packageName 应用包名
     * @return 是否正在运行
     */
    public static boolean isAppRunning(Context context, String packageName) {
        try {
            android.app.ActivityManager am = (android.app.ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            List<android.app.ActivityManager.RunningAppProcessInfo> runningProcesses = am.getRunningAppProcesses();

            if (runningProcesses != null) {
                for (android.app.ActivityManager.RunningAppProcessInfo processInfo : runningProcesses) {
                    if (packageName.equals(processInfo.processName)) {
                        Log.d("monitoru2", "应用正在运行: " + packageName + ", 重要性: " + processInfo.importance);
                        return true;
                    }
                }
            }

            Log.d("monitoru2", "应用未在运行: " + packageName);
            return false;
        } catch (Exception e) {
            Log.e("monitoru2", "检查应用运行状态失败: " + e.getMessage());
            return false;
        }
    }

    /**
     * 强制结束指定应用进程
     * @param context 上下文对象
     * @param packageName 要结束的应用包名
     * @throws Exception 操作失败时抛出异常
     */
    public static void appEnd(Context context, String packageName) throws Exception {
        Log.d("monitoru2", "使用ActivityManager强制结束应用进程: " + packageName);

        try {
            android.app.ActivityManager manager = (android.app.ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);

            if (manager != null) {
                manager.killBackgroundProcesses(packageName);
                Log.d("monitoru2", "✅ 成功调用killBackgroundProcesses: " + packageName);

                Thread.sleep(500);
                boolean stillRunning = isAppRunning(context, packageName);

                if (stillRunning) {
                    Log.w("monitoru2", "⚠️ 应用进程可能仍在运行,可能需要前台权限或应用有保护机制: " + packageName);
                } else {
                    Log.d("monitoru2", "✅ 应用进程已成功终止: " + packageName);
                }

            } else {
                Log.e("monitoru2", "❌ 无法获取ActivityManager服务");
                throw new Exception("无法获取ActivityManager服务");
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Log.e("monitoru2", "等待过程被中断: " + e.getMessage());
            throw new Exception("操作被中断: " + e.getMessage());
        } catch (Exception e) {
            Log.e("monitoru2", "结束应用进程异常: " + e.getMessage());
            throw new Exception("结束应用进程失败: " + e.getMessage());
        }
    }

    /**
     * 强制结束当前应用进程
     * @throws Exception 操作失败时抛出异常
     */
    public static void appEnd() throws Exception {
        Log.d("monitoru2", "强制结束当前应用进程");

        try {
            MyAccessibilityService service = MyAccessibilityService.getInstance();
            if (service != null) {
                Log.d("monitoru2", "无障碍服务实例获取成功,准备强制关闭当前应用");
                boolean success = service.forceCloseCurrentApp();
                if (success) {
                    Log.d("monitoru2", "强制关闭当前应用成功");
                } else {
                    Log.e("monitoru2", "强制关闭当前应用失败");
                    throw new Exception("无法强制关闭当前应用");
                }
            } else {
                Log.e("monitoru2", "无障碍服务实例为null,服务可能未启用");
                throw new Exception("无障碍服务未启用");
            }
        } catch (Exception e) {
            Log.e("monitoru2", "结束应用进程异常: " + e.getMessage());
            throw new Exception("结束应用进程失败: " + e.getMessage());
        }
    }
}

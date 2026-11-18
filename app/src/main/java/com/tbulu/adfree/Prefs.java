package com.tbulu.adfree;

import android.app.AndroidAppHelper;
import android.app.Application;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 功能开关配置（模块 App ↔ Hook 进程的桥梁）。
 *
 * 设计原则（v1.32 修订）：**不向两步路（官方 App）自己的数据目录写任何东西**。
 * 配置只存在于模块 App（com.sumver.tbulu）的私有偏好文件 settings 里，
 * Hook 进程通过标准 Android IPC 读取 / 回写。
 *
 * 通道：
 *  - 模块 App 侧：{@link Context#MODE_PRIVATE} 读写 settings，并通过只读 ContentProvider
 *    （{@link PrefsProvider}，authority 见 {@link #PROVIDER_AUTHORITY}）对外暴露 Boolean 开关；
 *  - Hook（两步路）侧：进程启动后异步查询 content://com.sumver.tbulu.prefs/settings，
 *    成功后缓存到进程内存；查询失败则按内置默认值行事，不阻塞两步路启动。
 *
 * 已知环境问题（真机 Xiaomi 15 Ultra / HyperOS + LSPosed 2.2.1）：
 *  - MIUI 会以「自启动」名义拦截「两步路 → 模块 App」的 provider 冷启动：
 *      WakePathChecker: MIUILOG-AutoStart ... Reject caller=com.lolaage.tbulu.tools
 *                       callee=com.sumver.tbulu classname=com.tbulu.adfree.PrefsProvider
 *    对策：把模块 App 加入 MIUI 自启动/后台白名单（或先打开一次模块 App 让它常驻）。
 *  - LSPosed 的 XSharedPreferences 在 2.2.1 运行期直接 ClassNotFoundException
 *    （不再向模块暴露该类），故不作为通道，模块也不再声明 xposedsharedprefs。
 *
 * 读取策略：**每个进程只加载一次**。因此开关修改后必须重启（强制停止）两步路才生效
 * —— 刻意设计，避免运行中状态错乱（弹窗里改完会提示）。
 */
final class Prefs {

    /** 与 app/build.gradle 的 applicationId 保持一致（不依赖 BuildConfig 的开关）。 */
    static final String MODULE_PKG = "com.sumver.tbulu";
    /** 被 Hook 的目标应用包名（供模块 App 进程校验写入来源，避免直接引用 Xposed API）。 */
    static final String TARGET_PKG = "com.lolaage.tbulu.tools";
    /** 设置界面与 Hook 共用同一偏好文件名。 */
    static final String FILE = "settings";

    /**
     * 并入单个 APK（LSPatch 本地模式等：模块与目标同进程、无独立模块 App）后，
     * 配置落在**宿主应用自己**的偏好文件；独立文件名以避免与宿主同名文件冲突。
     */
    static final String HOST_FILE = "tbulu_adfree_settings";

    /**
     * 配置桥（只读 ContentProvider）authority：= applicationId + ".prefs"。
     * 必须与 AndroidManifest.xml 中 provider 的 android:authorities 完全一致。
     */
    static final String PROVIDER_AUTHORITY = MODULE_PKG + ".prefs";
    /** Hook 进程查询用的 URI。 */
    static final Uri PROVIDER_URI = Uri.parse("content://" + PROVIDER_AUTHORITY + "/" + FILE);
    /** 游标列名（与 {@link PrefsProvider} 约定）。 */
    static final String COLUMN_KEY = "key";
    static final String COLUMN_VALUE = "value";

    // ---- 开关 key（设置界面 / Hook 共用）----
    /** 隐藏各类广告。 */
    static final String K_ADS = "ads";
    /** "我的"页会员促销横幅（含遗留空档压缩）。 */
    static final String K_VIP_BANNER = "vip_banner";
    /** "我的"页顶部 VIP 客服按钮。 */
    static final String K_VIP_SERVICE = "vip_service";
    /** 底部 dock 栏红点。 */
    static final String K_DOCK_BADGE = "dock_badge";
    /**
     * 「我的」页右上角消息红点（未读数角标；消息入口保留，仍可点击进消息中心）。默认关闭。
     * key 末尾带 2：v1.22 测试期该开关曾默认开启，存量配置里已被写成 true；
     * 换 key 让旧值作废，保证"默认关闭"对新装/已装用户都生效（想开启在设置界面手动打开）。
     */
    static final String K_MY_MESSAGE_BADGE = "my_message_badge2";
    /** 升级弹窗（版本详情 + 我的页更新提示）。 */
    static final String K_UPGRADE = "upgrade_dialog";
    /** 装备 tab 防误触。 */
    static final String K_EQUIPMENT = "equipment_confirm";
    /** 会员权益：图片水印。 */
    static final String K_VIP_WATERMARK = "vip_watermark";
    /** 会员权益：DIY首页（地图设为启动界面）。 */
    static final String K_VIP_DIY_HOME = "vip_diy_home";
    /** 会员权益：头像挂件。 */
    static final String K_VIP_PENDANT = "vip_pendant";
    /** 会员权益：专属样式（记录/导航皮肤 + 桌面图标）。 */
    static final String K_VIP_STYLE = "vip_style";
    /** 会员权益：个性化分享（高级数据分享图）。 */
    static final String K_VIP_SHARE = "vip_share";
    /** 会员权益：轨迹叠加上限（非会员 10 条，解锁按会员上限 100 条）。 */
    static final String K_VIP_OVERLAY_TRACKS = "vip_overlay_tracks";
    /** 会员权益：轨迹列表「合并/导出」的批量导出、批量分享（非会员弹开通引导）。 */
    static final String K_VIP_BULK_EXPORT_SHARE = "vip_bulk_export_share";
    /** 分享卡顶部文案净化（打卡/累计分享图）。 */
    static final String K_SHARE_CLEAN = "share_card_clean";
    /** 自定义分享卡底部文案（长按输入）。 */
    static final String K_SHARE_CUSTOM = "share_card_custom";
    /**
     * 打卡分享卡：隐藏「坚持运动」天数并把「本次运动」移到左侧（v1.20 起默认关闭）。
     * key 末尾带 2：v1.17~v1.19 期间该开关默认开启，存量配置里已被写成 true；
     * 换 key 让旧值作废，保证"默认关闭"对新装/已装用户都生效（想开启在设置界面手动打开）。
     */
    static final String K_SHARE_HIDE_DAYS = "share_card_hide_days2";
    /** 隐藏分享卡右上角「两步路」logo 水印。 */
    static final String K_SHARE_LOGO = "share_card_logo";

    /** 全部开关 key（设置界面用于写默认值）。 */
    static String[] allKeys() {
        return new String[]{
                K_ADS,
                K_VIP_BANNER,
                K_VIP_SERVICE,
                K_DOCK_BADGE,
                K_MY_MESSAGE_BADGE,
                K_UPGRADE,
                K_EQUIPMENT,
                K_VIP_WATERMARK,
                K_VIP_DIY_HOME,
                K_VIP_PENDANT,
                K_VIP_STYLE,
                K_VIP_SHARE,
                K_VIP_OVERLAY_TRACKS,
                K_VIP_BULK_EXPORT_SHARE,
                K_SHARE_CLEAN,
                K_SHARE_CUSTOM,
                K_SHARE_HIDE_DAYS,
                K_SHARE_LOGO,
        };
    }

    /** 未配置时的默认值：仅下列 key 默认关闭，其余全部默认开启。 */
    private static final Set<String> DEFAULT_OFF = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(K_SHARE_HIDE_DAYS, K_MY_MESSAGE_BADGE)));

    /** 开关的默认值（设置界面写入默认值、Hook 侧读取回退共用）。 */
    static boolean defaultOf(String key) {
        return !DEFAULT_OFF.contains(key);
    }

    /** Hook 进程的配置快照；null = 尚未加载完成（此时按默认值行事）。 */
    private static volatile Map<String, Boolean> sSnapshot;

    /**
     * 本进程内被就地改写的开关（来源：两步路内嵌的「模块设置」弹窗，见 {@link SidebarSettingsHook}）。
     * 优先级高于 {@link #sSnapshot}：同一进程的 Hook 读取时立即生效、无需重启；
     * 同时会持久化到配置源（独立模块 App → PrefsProvider；并入单 APK 后 → 本地偏好），
     * 因此下一次启动仍然保留。
     */
    private static final Map<String, Boolean> sOverrides = new ConcurrentHashMap<>();

    private static final Object sLoadLock = new Object();
    /** 是否已发起加载（每个进程只发起一轮；失败会固定为空快照，不再重试）。 */
    private static boolean sLoadStarted;
    /** provider 错误日志只输出一次（避免刷屏）。 */
    private static boolean sProviderErrorLogged;

    /** 等待目标应用 Application 创建的上限与轮询间隔（毫秒）。 */
    private static final long CONTEXT_WAIT_MS = 1500L;
    private static final long CONTEXT_POLL_MS = 50L;
    /** provider 查询的最大尝试次数。 */
    private static final int MAX_LOAD_ATTEMPTS = 3;

    private Prefs() {
    }

    /**
     * 模块 App 进程使用：读写设置文件（MODE_PRIVATE）。
     * 偏好文件即 {@link PrefsProvider} 对外暴露的数据源，无需任何跨进程权限处理。
     */
    static SharedPreferences ui(Context context) {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /**
     * 宿主应用本地配置（并入单 APK / LSPatch 本地模式的持久化位置）。
     * 模块 App 进程不使用它（模块 App 用 {@link #ui}）。
     */
    static SharedPreferences hostPrefs(Context context) {
        return context.getSharedPreferences(HOST_FILE, Context.MODE_PRIVATE);
    }

    /**
     * Hook 进程使用：开关是否开启（快照未就绪 / 读取失败 = 内置默认值）。
     * 不阻塞：首次调用会发起异步加载，快照就绪后各次调用即返回真实配置。
     */
    static boolean isEnabled(String key) {
        Boolean override = sOverrides.get(key);
        if (override != null) {
            return override;
        }
        Map<String, Boolean> snapshot = sSnapshot;
        if (snapshot == null) {
            ensureLoadStarted();
            return defaultOf(key);
        }
        Boolean value = snapshot.get(key);
        return value == null ? defaultOf(key) : value;
    }

    /**
     * 就地修改开关（供两步路内嵌的「模块设置」弹窗使用）：
     *  1) 同步更新本进程内存值 → 同进程的 Hook 立即按新值行事（无需重启）；
     *  2) 后台线程持久化到配置源 → 下次启动仍然保留：
     *     - 独立模块 App 形态：经 {@link PrefsProvider} 写入模块 App 的偏好文件；
     *     - 并入单个 APK（LSPatch 本地模式等，无独立模块 App）：写宿主应用本地 {@link #HOST_FILE}。
     *
     * 持久化放后台线程，避免跨进程写入在 UI 线程阻塞（首次写入会拉起模块 App 进程）。
     */
    static void setEnabled(Context context, String key, boolean value) {
        sOverrides.put(key, value);
        if (context == null) {
            return;
        }
        final Context appContext = context.getApplicationContext() != null
                ? context.getApplicationContext() : context;
        final String fKey = key;
        final boolean fValue = value;
        Thread writer = new Thread(new Runnable() {
            @Override
            public void run() {
                persist(appContext, fKey, fValue);
            }
        }, "TbuluAdFree-PrefsWrite");
        writer.setDaemon(true);
        writer.start();
    }

    /** 持久化单个开关到配置源（后台线程调用）。 */
    private static void persist(Context context, String key, boolean value) {
        try {
            if (MODULE_PKG.equals(context.getPackageName())) {
                ui(context).edit().putBoolean(key, value).apply();
                debugLog(key + " = " + value + " persisted (module app local)");
                return;
            }
            if (!isModuleAppInstalled(context)) {
                // 并入单 APK / LSPatch 本地模式：没有独立模块 App，只能写宿主本地
                hostPrefs(context).edit().putBoolean(key, value).apply();
                debugLog(key + " = " + value + " persisted (host-local; merged single-APK mode)");
                return;
            }
            ContentValues values = new ContentValues();
            values.put(COLUMN_KEY, key);
            values.put(COLUMN_VALUE, value ? 1 : 0);
            int rows = context.getContentResolver().update(PROVIDER_URI, values, null, null);
            if (rows > 0) {
                debugLog(key + " = " + value + " persisted to module app");
            } else {
                debugLog(key + " = " + value + " NOT persisted: module app provider rejected"
                        + " (check MIUI autostart/background permission for " + MODULE_PKG + ")");
            }
        } catch (Throwable t) {
            debugLog("persist failed " + key + "=" + value + ": " + t);
        }
    }

    /** key 是否属于已知开关（用于 Provider 写入口的合法性校验）。 */
    static boolean isKnownKey(String key) {
        if (key == null) {
            return false;
        }
        for (String known : allKeys()) {
            if (known.equals(key)) {
                return true;
            }
        }
        return false;
    }

    /** 供 XposedInit 在进程启动时尽早触发配置加载（异步、不阻塞）。 */
    static void prime() {
        ensureLoadStarted();
    }

    /** 幂等：每个进程只发起一轮异步加载。 */
    private static void ensureLoadStarted() {
        synchronized (sLoadLock) {
            if (sLoadStarted || sSnapshot != null) {
                return;
            }
            sLoadStarted = true;
        }
        Thread loader = new Thread(new Runnable() {
            @Override
            public void run() {
                loadSnapshot();
            }
        }, "TbuluAdFree-Prefs");
        loader.setDaemon(true);
        loader.start();
    }

    /**
     * 后台加载：从模块 App 的只读 ContentProvider 取整份开关快照（每个进程只做一次）。
     *
     * 不读取（也不写入）两步路自己的偏好文件 —— 配置只属于模块 App。
     * 唯一例外：并入单 APK / LSPatch 本地模式（**没有**独立模块 App）时，配置只能落在宿主
     * 本地偏好里，那是该产品形态下唯一可行的位置。
     *
     * 查询失败（典型原因：MIUI 自启动拦截）→ 空快照 → 全部走内置默认值，不影响 App 运行。
     */
    private static void loadSnapshot() {
        Map<String, Boolean> loaded = new HashMap<>();
        Application app = waitForApplication();
        if (app == null) {
            debugLog("Application not ready; built-in defaults");
            sSnapshot = loaded;
            return;
        }

        if (!isModuleAppInstalled(app)) {
            // 并入单 APK / LSPatch 本地模式：没有独立模块 App，只能读宿主本地
            Map<String, Boolean> local = readHostPrefs(app);
            sSnapshot = local;
            debugLog("module app absent; loaded " + local.size()
                    + " switches via host-local prefs (merged single-APK mode)");
            return;
        }

        for (int attempt = 1; attempt <= MAX_LOAD_ATTEMPTS; attempt++) {
            Map<String, Boolean> fromApp = queryProvider(app);
            if (fromApp != null) {
                loaded.putAll(fromApp);
                break;
            }
            debugLog("PrefsProvider attempt " + attempt + " failed"
                    + " (check MIUI autostart/background permission for " + MODULE_PKG + ")");
            if (attempt < MAX_LOAD_ATTEMPTS) {
                sleepQuietly(300L * attempt);
            }
        }

        sSnapshot = loaded;
        debugLog("snapshot ready: " + loaded.size() + " switches"
                + (loaded.isEmpty() ? " (built-in defaults)" : " (from module app)"));
    }

    /** 读取宿主应用本地配置（并入单 APK 场景；只认已知 switch key）。 */
    private static Map<String, Boolean> readHostPrefs(Context context) {
        Map<String, Boolean> out = new HashMap<>();
        try {
            for (Map.Entry<String, ?> entry : hostPrefs(context).getAll().entrySet()) {
                Object value = entry.getValue();
                if (value instanceof Boolean && isKnownKey(entry.getKey())) {
                    out.put(entry.getKey(), (Boolean) value);
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** 是否存在独立的模块 App（并入单 APK / LSPatch 本地模式时不存在）。 */
    private static boolean isModuleAppInstalled(Context context) {
        try {
            context.getPackageManager().getPackageInfo(MODULE_PKG, 0);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 等待目标应用 Application 创建（Hook 进程启动早期可能为 null）。 */
    private static Application waitForApplication() {
        long deadline = System.currentTimeMillis() + CONTEXT_WAIT_MS;
        do {
            try {
                Application app = AndroidAppHelper.currentApplication();
                if (app != null) {
                    return app;
                }
            } catch (Throwable ignored) {
                // ActivityThread 尚未就绪等场景：继续等待
            }
            sleepQuietly(CONTEXT_POLL_MS);
        } while (System.currentTimeMillis() < deadline);
        return null;
    }

    /** 查询模块 App 的只读 ContentProvider；失败返回 null（由调用方决定是否重试）。 */
    private static Map<String, Boolean> queryProvider(Context context) {
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(PROVIDER_URI, null, null, null, null);
            if (cursor == null) {
                logProviderError("query returned null");
                return null;
            }
            int keyIndex = cursor.getColumnIndex(COLUMN_KEY);
            int valueIndex = cursor.getColumnIndex(COLUMN_VALUE);
            if (keyIndex < 0 || valueIndex < 0) {
                logProviderError("unexpected cursor columns");
                return null;
            }
            Map<String, Boolean> loaded = new HashMap<>();
            while (cursor.moveToNext()) {
                String key = cursor.getString(keyIndex);
                if (key != null) {
                    loaded.put(key, cursor.getInt(valueIndex) != 0);
                }
            }
            return loaded;
        } catch (Throwable t) {
            logProviderError(String.valueOf(t));
            return null;
        } finally {
            if (cursor != null) {
                try {
                    cursor.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static void logProviderError(String reason) {
        if (sProviderErrorLogged) {
            return;
        }
        sProviderErrorLogged = true;
        XposedInit.log("Prefs: provider query failed: " + reason);
    }

    /**
     * 同时写 logcat（tag = TbuluAdFree）与 XposedBridge 日志。
     *
     * 配置通道是否生效只有运行期才能看出来（MIUI 拦截 provider、XSharedPreferences 是否可用），
     * 而 XposedBridge 日志需要 root 才能读；logcat 里的目标进程日志普通 adb 就能看，
     * 排查“开关为什么不生效”时直接用 `adb logcat -s TbuluAdFree` 即可。
     */
    private static void debugLog(String msg) {
        try {
            // 注意：HyperOS/MIUI 会过滤第三方应用的 INFO 级日志，这里用 WARN 才能在 logcat 看到
            android.util.Log.w("TbuluAdFree", "Prefs: " + msg);
        } catch (Throwable ignored) {
        }
        XposedInit.log("Prefs: " + msg);
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}

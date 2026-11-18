package com.tbulu.adfree;

/**
 * 两步路「版本档案」：把每个 App 版本里**易变的锚点**（类名/方法名）集中成数据，
 * 由入口 {@link XposedInit} 读取 versionCode + versionName 后一次性分派，各功能内部不再判断版本。
 *
 * 设计原则（见 README-技术文档「多版本兼容」）：
 *  - 新增版本 = 新增一份档案 + 注册区间，而不是改所有 Hook；
 *  - 每个版本的候选名只包含该版本真实存在的名字，避免“新名误命中旧版”；
 *  - 区间覆盖热修复版本；未知版本复用最新档案并标记 known=false，锚点解析失败即降级；
 *  - 与版本无关的逻辑（Prefs/日志/弹窗/通用工具）保持共享，不复制。
 */
final class TargetProfile {

    // 已知版本区间（versionCode）
    static final int V928_MIN = 1650;
    static final int V928_MAX = 1655; // 9.2.8（1652）及其热修复
    static final int V929_MIN = 1656;
    static final int V929_MAX = 1660; // 9.2.9（1656）及其热修复
    /**
     * 9.3.0 = versionCode 1665。
     * 注意：9.2.9 的旧区间上限曾是 1665，会把 9.3.0 误判成 9.2.9（混淆名已漂移），
     * 因此这里把 V929 收紧到 1660，并用 versionName（"9.3.x"）优先分流。
     */
    static final int V930_MIN = 1661;
    static final int V930_MAX = 1675;

    /** 档案是否对应一个明确支持的版本（false = 未知版本，走最新档案 + 降级）。 */
    final boolean known;
    final int minVersionCode;
    final int maxVersionCode;
    /** 展示用版本号，例如 9.2.8 / 9.2.9 / 9.3.0。 */
    final String displayVersion;

    /** 会员判断单例（Kotlin object）。 */
    final String[] authSingletonNames;
    /** “VIP 或 SVIP”判断方法名（无参 -> boolean）。 */
    final String[] vipOrSvipMethodNames;
    /** “SVIP”判断方法名（无参 -> boolean）。 */
    final String[] svipMethodNames;
    /** 轨迹叠加配额外层类。 */
    final String[] overlayQuotaNames;
    /** 叠加确认链路 continuation：$Oooo000 / $o000oOoO。 */
    final String[] overlayBranch1Names;
    final String[] overlayBranch2Names;
    /** 桌面图标点击监听（匿名内部类）。 */
    final String[] appLogoListenerNames;
    /** 挂件选择权限方法（(int)->boolean）。 */
    final String[] pendantPermissionMethods;
    /** “我的”页 Fragment 类（可多个，逐个安装）。 */
    final String[] fragmentNames;
    /** 消息红点 continuation 类。 */
    final String[] msgBadgeContinuationNames;
    /** 批量导出/分享回调方法（参数为 1 个 Integer）。 */
    final String[] bulkExportMethods;
    /**
     * 开屏广告是否允许「直接掐断展示入口」（Oooo0 / Oooo0O0 返回 null）。
     *
     * 9.2.8 / 9.2.9：App 另有独立计时器进入主界面，掐断入口是安全的，保持旧行为。
     * 9.3.0：入口 {@code Oooo0O0(WelcomeNormalFragment$OooO0O0)} 同时负责注册
     *        “开屏结束”回调（WelcomeActivity.OoooO0() 里 new oOo00o00(activity)），
     *        掐断后回调永不触发 → WelcomeActivity 永久停留 = 用户看到的“卡在第一页”。
     *        因此 9.3.0 起只走 App 自带的「本次不展示开屏广告」分支
     *        （AdConfigManager.isNeedShowSplashAdThisTime -> false）。
     */
    final boolean suppressSplashEntry;

    private TargetProfile(boolean known, int minVersionCode, int maxVersionCode,
                          String displayVersion,
                          String[] authSingletonNames,
                          String[] vipOrSvipMethodNames,
                          String[] svipMethodNames,
                          String[] overlayQuotaNames,
                          String[] overlayBranch1Names,
                          String[] overlayBranch2Names,
                          String[] appLogoListenerNames,
                          String[] pendantPermissionMethods,
                          String[] fragmentNames,
                          String[] msgBadgeContinuationNames,
                          String[] bulkExportMethods,
                          boolean suppressSplashEntry) {
        this.known = known;
        this.minVersionCode = minVersionCode;
        this.maxVersionCode = maxVersionCode;
        this.displayVersion = displayVersion;
        this.authSingletonNames = authSingletonNames;
        this.vipOrSvipMethodNames = vipOrSvipMethodNames;
        this.svipMethodNames = svipMethodNames;
        this.overlayQuotaNames = overlayQuotaNames;
        this.overlayBranch1Names = overlayBranch1Names;
        this.overlayBranch2Names = overlayBranch2Names;
        this.appLogoListenerNames = appLogoListenerNames;
        this.pendantPermissionMethods = pendantPermissionMethods;
        this.fragmentNames = fragmentNames;
        this.msgBadgeContinuationNames = msgBadgeContinuationNames;
        this.bulkExportMethods = bulkExportMethods;
        this.suppressSplashEntry = suppressSplashEntry;
    }

    private TargetProfile asUnknown() {
        return new TargetProfile(false, -1, -1, displayVersion,
                authSingletonNames, vipOrSvipMethodNames, svipMethodNames,
                overlayQuotaNames, overlayBranch1Names, overlayBranch2Names,
                appLogoListenerNames, pendantPermissionMethods, fragmentNames,
                msgBadgeContinuationNames, bulkExportMethods, suppressSplashEntry);
    }

    /** 已知支持版本的展示串，例如 “9.2.8、9.2.9、9.3.0”。 */
    static String supportedVersions() {
        StringBuilder sb = new StringBuilder();
        TargetProfile[] all = {V928, V929, V930};
        for (TargetProfile p : all) {
            if (!p.known || p.displayVersion == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("、");
            }
            sb.append(p.displayVersion);
        }
        return sb.toString();
    }

    String describe() {
        return known
                ? ("v" + minVersionCode + "-v" + maxVersionCode + " (" + displayVersion + ")")
                : "unknown(newest-profile " + displayVersion + ")";
    }

    /**
     * 版本分派：**versionName 优先**（同一 versionCode 区间可能被不同版本复用，例如
     * 9.2.9 的旧区间上限 1665 与 9.3.0 的 1665 撞车），versionName 不可用时退回 versionCode 区间。
     */
    static TargetProfile forVersion(int versionCode, String versionName) {
        if (versionName != null) {
            if (versionName.startsWith("9.3")) {
                return V930;
            }
            if (versionName.startsWith("9.2.9")) {
                return V929;
            }
            if (versionName.startsWith("9.2.8")) {
                return V928;
            }
        }
        if (versionCode >= V928_MIN && versionCode <= V928_MAX) {
            return V928;
        }
        if (versionCode >= V929_MIN && versionCode <= V929_MAX) {
            return V929;
        }
        if (versionCode >= V930_MIN && versionCode <= V930_MAX) {
            return V930;
        }
        // 未知版本：复用最新档案，但标记 unknown，锚点解析失败会优雅降级
        return V930.asUnknown();
    }

    static TargetProfile forVersion(int versionCode) {
        return forVersion(versionCode, null);
    }

    // ------------------------------------------------------------------
    // 9.2.8（1652）
    // 注意：这里只放 9.2.8 真实存在的名字，不能混入 9.2.9 的新名，
    // 否则会像 o00000oo 那样“新名误命中旧版”。
    // ------------------------------------------------------------------
    private static final TargetProfile V928 = new TargetProfile(
            true, V928_MIN, V928_MAX, "9.2.8",
            new String[]{"o00OOOOo.OooO0O0"},
            new String[]{"OooOooO"},
            new String[]{"OooOoo0"},
            new String[]{"o00O.OooO0O0"},
            new String[]{"o00O.OooO0O0$Oooo000"},
            new String[]{"o00O.OooO0O0$o000oOoO"},
            new String[]{"com.lolaage.tbulu.tools.ChangeAppLogoActivity$mAdapter$2$OooO00o"},
            new String[]{"Oooo0O0"},
            new String[]{"com.lolaage.tbulu.tools.ui.fragment.main.o0000"},
            new String[]{"com.lolaage.tbulu.tools.ui.fragment.main.o0000$OooOo"},
            new String[]{"o00000oO"},
            true
    );

    // ------------------------------------------------------------------
    // 9.2.9（1656）
    // ------------------------------------------------------------------
    private static final TargetProfile V929 = new TargetProfile(
            true, V929_MIN, V929_MAX, "9.2.9",
            new String[]{"o00O0oOO.o0OoOo0", "o00OOOOo.OooO0O0"},
            new String[]{"OooOooO"},
            new String[]{"OooOoo0"},
            new String[]{"o00O0O0O.OooO0O0", "o00O.OooO0O0"},
            new String[]{"o00O0O0O.OooO0O0$Oooo000", "o00O.OooO0O0$Oooo000"},
            new String[]{"o00O0O0O.OooO0O0$o000oOoO", "o00O.OooO0O0$o000oOoO"},
            new String[]{"com.lolaage.tbulu.tools.ChangeAppLogoActivity$mAdapter$2$1",
                    "com.lolaage.tbulu.tools.ChangeAppLogoActivity$mAdapter$2$OooO00o"},
            new String[]{"Oooo0", "Oooo0O0"},
            new String[]{"com.lolaage.tbulu.tools.ui.fragment.main.o0000",
                    "com.lolaage.tbulu.tools.ui.fragment.main.SetUpFragment"},
            new String[]{"com.lolaage.tbulu.tools.ui.fragment.main.SetUpFragment$OooOo",
                    "com.lolaage.tbulu.tools.ui.fragment.main.o0000$OooOo"},
            new String[]{"o00000oo", "o00000oO"},
            true
    );

    // ------------------------------------------------------------------
    // 9.3.0（1665）——锚点由 9.3.0 安装包运行时内存 dex 实测核对（2026-11-18）
    //
    // 实测差异（相对 9.2.9）：
    //  - 会员判断单例：o00O0oOO.o0OoOo0 → **o00O0ooo.Oooo0**
    //      OooOooo() = OooOoo0() || OooOoo()   ← 等价 9.2.9 的 OooOooO（VIP 或 SVIP）
    //      OooOoo0()                           ← 仍是 SVIP
    //      （o00OOOOo.OooO0O0 在 9.3.0 已退化为只有 <init>/onClick 的监听类，不能再当单例）
    //  - “我的”页 Fragment：ui.fragment.main.o0000 已退化为监听类（只有 <init>/onClick），
    //    只剩 SetUpFragment（含 o000OOo0 / OooOo0 / onResume / onHiddenChanged / o0000oo0）。
    //  - 桌面图标点击监听：ChangeAppLogoActivity$mAdapter$2$1（含 OooOo00()）仍在。
    //  - 开屏广告：见 {@link #suppressSplashEntry} 的说明，9.3.0 起不再掐断展示入口。
    //  - 轨迹叠加配额 o00O0O0O.OooO0O0 类还在，但 OooOOO 方法与 $Oooo000/$o000oOoO
    //    子类在 9.3.0 已漂移（解析失败会自动跳过，暂不影响 App 运行）。
    // ------------------------------------------------------------------
    private static final TargetProfile V930 = new TargetProfile(
            true, V930_MIN, V930_MAX, "9.3.0",
            new String[]{"o00O0ooo.Oooo0"},
            new String[]{"OooOooo", "OooOooO"},
            new String[]{"OooOoo0"},
            new String[]{"o00O0O0O.OooO0O0", "o00O.OooO0O0"},
            new String[]{"o00O0O0O.OooO0O0$Oooo000", "o00O.OooO0O0$Oooo000"},
            new String[]{"o00O0O0O.OooO0O0$o000oOoO", "o00O.OooO0O0$o000oOoO"},
            new String[]{"com.lolaage.tbulu.tools.ChangeAppLogoActivity$mAdapter$2$1"},
            new String[]{"Oooo0", "Oooo0O0"},
            new String[]{"com.lolaage.tbulu.tools.ui.fragment.main.SetUpFragment"},
            new String[]{"com.lolaage.tbulu.tools.ui.fragment.main.SetUpFragment$OooOo"},
            new String[]{"o00000oo", "o00000oO"},
            false
    );

    private TargetProfile() {
        throw new AssertionError();
    }
}

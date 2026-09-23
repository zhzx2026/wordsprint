package com.aidemo.wordsprint;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 应用内更新：拉取 update.json → 比 versionCode → 下载到 app 私有目录 → 自供 ContentProvider 拉起安装器。
 * update.json: {"versionCode":7,"versionName":"1.0.6","url":"…apk(可相对)","notes":"…","force":false}
 */
public class Update {
    public static class Info {
        public int code;
        public String name = "", url = "", notes = "";
        public boolean force;
    }

    public interface Cb { void onResult(Info info, String err); }

    /**
     * 检查结果（不管有没有新版本都给）——用户反馈「显示已经是最新版本，但明明有新包」，
     * 根因就是界面只说结论、不说依据。现在把「查了哪个通道、服务器上是什么版本、本机是什么版本」
     * 全带回来，设置页直接写出来，一眼就能看出是不是查错了通道。
     */
    public static class Res {
        public Info server;          // 服务器上的版本（查到了才非空；可能比本机旧）
        public boolean newer;        // 服务器版本是不是比本机新
        public int channel;          // 0 = stable（正式版）· 2 = branch（指定分支）
        public String url = "";      // 实际请求的地址
        public String err;           // 失败原因（null = 请求成功）
    }

    public interface Cb2 { void onRes(Res r); }

    public static String channelName(Context c, int ch) {
        if (ch == UpCh.BRANCH) {
            String id = Prefs.of(c).upBranch();
            String b = c.getString(R.string.update_src_branch);
            return id.isEmpty() ? b : b + "·" + id;
        }
        return c.getString(R.string.update_src_stable);
    }

    private static Info pending;   // 等待用户授予安装权限后继续

    // ---------- 下载进度（对外只读，供设置页/首页/进度弹窗显示） ----------
    // 以前进度只画在「下载中」那个弹窗里，弹窗要是被系统压到后面（或者授权页刚返回），
    // 用户就完全看不到有没有在下载 —— 用户反馈「更新进度条没有」。现在进度也是全局状态，
    // 哪个页面在台上哪个页面显示。
    private static volatile boolean busy;
    private static volatile int pct = -1;          // -1 = 还不确定百分比（服务端没给长度）
    private static volatile int stage = DlProg.CONNECT;
    private static volatile String line = "";
    private static volatile Info latest;           // 最近一次查到的新版本（首页横幅用）

    public static boolean isBusy() { return busy; }
    public static int progressPct() { return pct; }
    public static int progressStage() { return stage; }
    public static String progressLine() { return line; }
    public static Info newest() { return latest; }
    public static void clearNewest() { latest = null; }

    // ---------- 进度弹窗（唯一的进度入口：用户 2026-09-15「进度条是弹窗不是设置界面」） ----------
    private static AlertDialog progressDlg;
    private static Activity progressHost;
    private static Runnable followHook;           // 进度弹窗那个 300ms 自刷新（关窗时要撤掉）
    private static View followView;               // followHook 挂在哪个视图上（撤回调要用）
    private static String progressName = "";      // 正在下的版本号（重挂弹窗时标题要写对）
    private static boolean uiBroken;              // 这一轮进度弹窗挂不上去（别再每 400ms 重试一次）
    private static Runnable cancelHook;           // 当前下载的「取消」动作

    public static int myCode(Context c) {
        try { return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionCode; }
        catch (Exception e) { return 0; }
    }
    public static String myName(Context c) {
        try { return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName; }
        catch (Exception e) { return "?"; }
    }

    /** 返回非 null = 有更新（老接口，保留给「只关心有没有更新」的地方） */
    public static Info check(Context c) throws Exception {
        Res r = checkRes(c);
        if (r.err != null) throw new Exception(r.err);
        return r.newer ? r.server : null;
    }

    /**
     * 完整检查：请求当前通道的 update.json，把服务器版本与本机版本比一比。
     * 不抛异常 —— 失败原因放在 {@link Res#err}，界面可以照实显示（HTTP 码 / 连不上 / 没配置）。
     *
     * 装的是测试包（版本号 X.Y）时默认盯「分支」通道（用户 2026-09-15 装机实测：
     * 测试包盯正式源永远「已经是最新版本」）；dev 聚合档已退役（2026-09-22「安装界面 dev 还在」），
     * 显式选了 stable 就完全按 stable 来，不再替用户偷看别的源。
     */
    public static Res checkRes(Context c) {
        if (BuildInfo.SBS) {          // 同机双装包（包名带后缀）：应用内更新的 APK 是正式包名，装不上只会白报错
            Res r = new Res();
            r.err = "同机双装测试包不支持应用内更新，请从 GitHub Actions 的 staging artifact 手动下载安装";
            return r;
        }
        return fetch(c, Prefs.of(c).updateChannel());
    }

    /** 只查一个通道 */
    private static Res fetch(Context c, int channel) {
        Res r = new Res();
        r.channel = channel;
        try {
            String raw;
            if (channel == UpCh.BRANCH) {
                // 分支坑位：GitHub 预发布 Release `ci` 里该分支自己的 update-<id>.json
                //（用户 2026-09-22「apk 直接连 github 看分支」；dev 聚合分支与 dev 档都已删）
                String slot = Prefs.of(c).upBranch();
                if (slot.isEmpty()) { r.err = c.getString(R.string.update_branch_none); return r; }
                raw = UpCh.branchUpdateUrl(c.getString(R.string.update_ci_base), slot);
            } else {
                raw = c.getString(R.string.update_default_src).trim();
            }
            if (raw.contains("YOUR_GITHUB")) { r.err = "GitHub 源未配置"; return r; }
            if (raw.isEmpty()) { r.err = "未设置更新源地址"; return r; }
            String u = raw.toLowerCase().endsWith(".json") ? raw
                    : raw + (raw.endsWith("/") ? "" : "/") + "update.json";
            r.url = u;
            JSONObject j = new JSONObject(httpGet(u));
            Info i = new Info();
            i.code = j.optInt("versionCode", 0);
            i.name = j.optString("versionName", String.valueOf(i.code));
            String rel = j.optString("url");
            if (rel.startsWith("http")) i.url = rel;
            else {
                int q = u.lastIndexOf('/');
                i.url = u.substring(0, q + 1) + rel;
            }
            i.notes = j.optString("notes", "");
            i.force = j.optBoolean("force", false);
            r.server = i;
            r.newer = i.code > myCode(c);
            return r;
        } catch (Throwable t) {
            r.err = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
            // 分支 404 = 那条分支还没出过测试包，照实说人话，别甩一个「HTTP 404」
            if (UpCh.BRANCH == channel && "HTTP 404".equals(r.err)) {
                r.err = c.getString(R.string.update_branch_empty);
            }
            return r;
        }
    }

    /** GET 一个小文本（update.json 这类）；非 2xx 抛异常，消息形如 "HTTP 404" */
    private static String httpGet(String u) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(u).openConnection();
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(10000);
        conn.setInstanceFollowRedirects(true);
        try {
            int sc = conn.getResponseCode();
            if (sc / 100 != 2) throw new Exception("HTTP " + sc);
            InputStream in = conn.getInputStream();
            StringBuilder sb = new StringBuilder();
            byte[] buf = new byte[4096];
            int n, tot = 0;
            while ((n = in.read(buf)) > 0 && tot < 256 * 1024) {
                sb.append(new String(buf, 0, n, "UTF-8"));
                tot += n;
            }
            in.close();
            return sb.toString();
        } finally { conn.disconnect(); }
    }

    // ---------- 分支清单（「分支」通道的选择行：读 ci 根 update.json 的 channels） ----------

    public interface BranchCb { void onRes(java.util.List<String> ids, String err); }

    private static volatile java.util.List<String> lastBranches;   // 最近一次成功拉到的分支 id

    public static java.util.List<String> lastBranches() { return lastBranches; }

    /**
     * 拉分支清单：读 ci 根 update.json 的 `channels` 数组（publish_ci.sh 每次构建都会用 ci 上
     * 现存的全部 update-&lt;id&gt;.json 资产重写它）。域名与下载同（github.com），能下包就一定能拉清单
     * —— 用户 2026-09-22「分支都没用，没反应」的教训：api.github.com 在手机网络下经常不通/匿名
     * 限流 403，清单永远拉不到。失败时回落上次结果，err 照实回调给界面，不静默。
     * 名单只含「出过测试包」的分支：新分支第一次构建后才进名单（没包的分支本来就没得选）。
     */
    public static void fetchBranchesAsync(final Activity a, final BranchCb cb) {
        new Thread(new Runnable() {
            @Override public void run() {
                java.util.List<String> ids = null;
                String err = null;
                try {
                    String base = a.getString(R.string.update_ci_base).trim();
                    while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
                    ids = UpCh.parseChannels(httpGet(base + "/update.json"));
                    if (ids.isEmpty()) err = a.getString(R.string.update_branch_list_empty);
                } catch (Throwable t) {
                    err = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
                }
                if (ids != null && !ids.isEmpty()) lastBranches = ids;
                else if (lastBranches != null) ids = lastBranches;   // 这回没拉到：亮上次的，别清空
                final java.util.List<String> ok = ids == null ? new java.util.ArrayList<String>() : ids;
                final String e = err;
                a.runOnUiThread(new Runnable() {
                    @Override public void run() { cb.onRes(ok, e); }
                });
            }
        }).start();
    }

    /** 异步版：回调里连「查了哪个通道、服务器什么版本」一起给 */
    public static void checkResAsync(final Activity a, final Cb2 cb) {
        new Thread(new Runnable() {
            @Override public void run() {
                final Res r = checkRes(a);
                a.runOnUiThread(new Runnable() {
                    @Override public void run() { cb.onRes(r); }
                });
            }
        }).start();
    }

    public static void checkAsync(final Activity a, final Cb cb) {
        checkResAsync(a, new Cb2() {
            @Override public void onRes(Res r) { cb.onResult(r.newer ? r.server : null, r.err); }
        });
    }

    /** 发现新版本 → 卡片弹窗（更新说明 + 立即更新/稍后） */
    public static void showFound(final Activity a, final Info info) {
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView head = new TextView(a);
        head.setText(a.getString(R.string.update_found_v, info.name, myName(a)));
        head.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        head.setTextColor(Skin.c(a, R.attr.wpText));
        col.addView(head, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (!info.notes.isEmpty()) {
            TextView notes = new TextView(a);
            notes.setText(info.notes);
            notes.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
            notes.setTextColor(Skin.c(a, R.attr.wpText2));
            notes.setLineSpacing(Ui.dp(a, 4), 1f);
            notes.setBackgroundResource(R.drawable.bg_card_field);
            int np = (int) Ui.dp(a, 11);
            notes.setPadding(np, np, np, np);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = (int) Ui.dp(a, 10);
            col.addView(notes, lp);
        }
        Ui.cardDialogPrimary(a, a.getString(R.string.update_title), Ui.scrollable(col, 300),
                a.getString(R.string.update_go), new Runnable() {
                    @Override public void run() { begin(a, info); }
                }, a.getString(R.string.update_later), null, true);
    }

    /** 安装权限检查 → 下载 → 拉起安装器 */
    private static void begin(Activity a, Info info) {
        if (busy) { showProgress(a); return; }      // 已在下载：别开第二条，把进度弹窗亮出来就行
        if (!canInstall(a)) {
            pending = info;
            Toast.makeText(a, R.string.update_need_perm, Toast.LENGTH_LONG).show();
            try {
                Intent it = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + a.getPackageName()));
                a.startActivity(it);
            } catch (Exception e) {
                Toast.makeText(a, R.string.update_perm_manual, Toast.LENGTH_LONG).show();
            }
            return;
        }
        download(a, info);
    }

    /**
     * 从系统设置授权页返回后调用：若有挂起的更新则继续。
     *
     * 以前只在首页 onResume 里调，从设置页点的更新一旦要授权，回来就**什么都不会发生**
     * （表现和「进度条坏了」一模一样）。现在三个入口都调它；权限没给也不再静默 —— 说一句为什么。
     */
    public static void resumePending(Activity a) {
        Info p = pending;
        if (p == null) return;
        if (canInstall(a)) { pending = null; download(a, p); return; }
        pending = null;
        try { Toast.makeText(a, R.string.update_need_perm, Toast.LENGTH_LONG).show(); } catch (Throwable ignored) {}
    }

    private static boolean canInstall(Context c) {
        if (Build.VERSION.SDK_INT < 26) return true;
        try { return c.getPackageManager().canRequestPackageInstalls(); } catch (Exception e) { return true; }
    }

    private static void download(final Activity a, final Info info) {
        final File f = new File(a.getExternalFilesDir(null), "update.apk");
        final boolean[] cancel = {false};
        cancelHook = new Runnable() {
            @Override public void run() { cancel[0] = true; }
        };
        busy = true;
        uiBroken = false;                  // 新一轮下载：进度弹窗重新给一次机会
        pct = -1;
        stage = DlProg.CONNECT;
        line = a.getString(R.string.update_downloading);
        try {
            presentProgress(a, info);
        } catch (Throwable t) {
            // 弹窗起不来也不能装作什么都没发生（用户只会看到「点了没反应」）：把原因说出来，
            // 下载照常跑完 —— 装包那一步不需要弹窗。
            uiBroken = true;
            try { Toast.makeText(a, a.getString(R.string.update_ui_fail, t.getClass().getSimpleName()),
                    Toast.LENGTH_LONG).show(); } catch (Throwable ignored) {}
        }
        new Thread(new Runnable() {
            @Override public void run() { runDownload(a, info, f, cancel); }
        }).start();
    }

    /** 阶段文案（连接中 / 下载中 / 校验 / 准备安装）—— 弹窗每 300ms 读一次 */
    private static String stageLabel(Context c, int st) {
        if (st == DlProg.VERIFY) return c.getString(R.string.update_verifying);
        if (st == DlProg.INSTALL) return c.getString(R.string.update_installing);
        if (st == DlProg.CONNECT) return c.getString(R.string.update_connecting);
        return c.getString(R.string.update_downloading);
    }

    /**
     * 建/重挂进度弹窗。下载中如果用户把弹窗关掉或切了页面，再点一次「立即更新」会走到这里，
     * 而不是开始第二次下载（{@link #showProgress}）。
     */
    private static void presentProgress(final Activity a, final Info info) {
        dismissProgress();
        progressHost = a;
        float d = a.getResources().getDisplayMetrics().density;
        // 进度条是**自己画**的（UpdateBar）：不再有 ProgressBar + drawable + level + tint 这条
        // 「任何一环失灵就变成有数字没条」的链路。算术与配色在纯 java 的 DlProg 里，有主机断言。
        final UpdateBar bar = new UpdateBar(a);
        final TextView st = new TextView(a);
        st.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        st.setTextColor(Skin.c(a, R.attr.wpText2));
        st.setText(R.string.update_downloading);
        // 弹窗不再「只在有进度事件时才动」：每 300ms 自己读一次全局进度。
        // 服务端没给 Content-Length 时百分比也会按已下载量估算（pct 不会是 -1），所以条子一定会动。
        final Runnable follow = new Runnable() {
            @Override public void run() {
                int p = progressPct();
                int s = progressStage();
                bar.setProgress(p, s);
                String l = line;
                st.setText(l == null || l.length() == 0 ? stageLabel(a, s) : l);
                if (!busy) return;
                bar.postDelayed(this, 300);
            }
        };
        followHook = follow;
        followView = bar;
        follow.run();                 // 立刻就显示当前状态，别让用户先盯 300ms 的空条
        bar.postDelayed(follow, 300);
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundResource(R.drawable.bg_card_28);
        int pad = (int) (18 * d);
        col.setPadding(pad, pad, pad, (int) (10 * d));
        TextView title = new TextView(a);
        title.setText(a.getString(R.string.update_downloading_title, info.name));
        progressName = info.name;
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16.5f);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        title.setTextColor(Skin.c(a, R.attr.wpText));
        col.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = (int) (10 * d);
        col.addView(st, slp);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (10 * d));
        plp.topMargin = (int) (10 * d);
        col.addView(bar, plp);
        TextView cancelBtn = new TextView(a);
        cancelBtn.setText(R.string.update_cancel);
        cancelBtn.setGravity(android.view.Gravity.CENTER);
        cancelBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        cancelBtn.setTextColor(Skin.c(a, R.attr.wpText2));
        cancelBtn.setBackgroundResource(R.drawable.bg_btn_outline);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (40 * d));
        clp.topMargin = (int) (14 * d);
        col.addView(cancelBtn, clp);
        cancelBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (cancelHook != null) cancelHook.run();
                dismissProgress();
                try { Toast.makeText(a, R.string.update_cancelled, Toast.LENGTH_SHORT).show(); } catch (Throwable ignored) {}
            }
        });
        // 走 Ui.presentCard：统一去掉系统对话框那层白面板（圆角外不再露白），且不可点外部误关
        progressDlg = Ui.presentCard(a, col, false);
    }

    /**
     * 下载中但看不到进度弹窗（用户切了页面 / 弹窗被系统收走）时，把它重新拉起来。
     * 判定条件里**必须带上 progressHost**：弹窗是挂在某个页面的窗口上的，用户从「关于与更新」页
     * 退回设置列表页，那个页面的窗口已经不在前面，可 `isShowing()` 还是 true ——
     * 结果就是「下载在跑、屏幕上什么都没有」（用户 2026-09-18「更新没有进度条」的其中一条路）。
     */
    public static void showProgress(Activity a) {
        if (!busy || cancelHook == null || uiBroken) return;
        if (progressDlg != null && progressHost == a && progressDlg.isShowing()) return;
        try {
            Info info = new Info();
            info.name = progressName == null || progressName.length() == 0 ? myName(a) : progressName;
            presentProgress(a, info);
        } catch (Throwable t) {
            // 弹窗挂不上（页面正在销毁之类）就算了，别把 App 带走：下载照常继续。
            // 置 uiBroken，免得轮询每 400ms 又试一次、一路建一堆废弹窗。
            progressDlg = null;
            uiBroken = true;
        }
    }

    private static void dismissProgress() {
        try { if (followView != null && followHook != null) followView.removeCallbacks(followHook); }
        catch (Throwable ignored) {}
        followHook = null;
        followView = null;
        try { if (progressDlg != null) progressDlg.dismiss(); } catch (Throwable ignored) {}
        progressDlg = null;
        progressHost = null;               // 别把 Activity 攥在静态字段里（关窗之后就用不到了）
    }

    /**
     * 下载 + 安装的调度：失败会**自动重试一次**。
     *
     * 用户 2026-09-16 要求「彻底修好」。除了进度条本身（见 {@link #barDrawable}），
     * 这里把「下载明明没完成却去装」这条最容易炸的路堵上：
     *   · 服务端给了 Content-Length → 字节数必须一分不差；
     *   · 再看能不能按 zip 打开、里面有没有 AndroidManifest.xml（被截断的包过不了）；
     * 两次都不行才报错，并且把更新卡片重新亮出来（对着同一版可以直接点「立即更新」重试）。
     */
    private static void runDownload(final Activity a, final Info info, final File f, final boolean[] cancel) {
        String err = null;
        for (int attempt = 0; attempt < 2 && !cancel[0]; attempt++) {
            try {
                if (attempt > 0) {                       // 重试前把进度条拉回起点，别让它停在上一轮的位置
                    stage = DlProg.CONNECT;
                    pct = 0;
                    line = a.getString(R.string.update_downloading);
                }
                fetch(a, info, f, cancel);
                if (cancel[0]) { finishQuietly(f); return; }
                stage = DlProg.INSTALL;                  // 校验过了：弹窗改说「准备安装」，别一声不响地消失
                line = a.getString(R.string.update_installing);
                busy = false;
                cancelHook = null;
                a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        dismissProgress();
                        install(a, f, info);
                    }
                });
                return;
            } catch (Throwable e) {
                // Throwable 而不是 Exception：漏出去的话 busy 会永远停在 true，
                // 之后每次点「立即更新」都只会重挂一个空弹窗 —— 看着就是「没有进度条」。
                if (cancel[0]) { finishQuietly(f); return; }
                err = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
        }
        finishQuietly(f);
        final String em = err;
        a.runOnUiThread(new Runnable() {
            @Override public void run() {
                dismissProgress();
                if (cancel[0] || a.isFinishing()) return;
                Toast.makeText(a, a.getString(R.string.update_fail, em), Toast.LENGTH_LONG).show();
                showFound(a, info);          // 网络抖一下不该就此卡死：卡片再亮一次，等于给个「重试」按钮
            }
        });
    }

    /** 收尾：复位下载状态、删掉半截文件（取消/失败共用） */
    private static void finishQuietly(File f) {
        busy = false;
        cancelHook = null;
        pct = -1;
        stage = DlProg.CONNECT;
        line = "";
        try { if (f != null && f.exists()) f.delete(); } catch (Throwable ignored) {}
    }

    /** 下完一次（成功返回；失败抛异常，交给 {@link #runDownload} 决定重不重试） */
    private static void fetch(final Activity a, final Info info, final File f, final boolean[] cancel) throws Exception {
        HttpURLConnection c = null;
        long startedAt = System.currentTimeMillis();
        try {
            c = (HttpURLConnection) new URL(info.url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(15000);
            c.setInstanceFollowRedirects(true);
            int sc = c.getResponseCode();
            if (sc / 100 != 2) throw new Exception("HTTP " + sc);
            long total = c.getContentLengthLong();
            InputStream in = c.getInputStream();
            FileOutputStream out = new FileOutputStream(f);
            byte[] buf = new byte[16384];
            long got = 0;
            int n;
            int lastPct = -1;
            stage = DlProg.DOWNLOAD;                     // 拿到第一个字节就算「下载中」
            while ((n = in.read(buf)) > 0) {
                if (cancel[0]) break;
                out.write(buf, 0, n);
                got += n;
                // 百分比与状态行都走 DlProg（纯 java，DlProgTest 有断言）：
                // 没给 Content-Length 时按已下量估算，最多 99% —— 不会再「一直停在 0%」。
                final int curPct = DlProg.pct(got, total);
                if (curPct != lastPct) {
                    lastPct = curPct;
                    final long t0 = System.currentTimeMillis() - startedAt;
                    pct = curPct;                                  // 全局进度（进度弹窗每 300ms 读它）
                    line = DlProg.line(a.getString(R.string.update_progress, curPct), got, total, t0);
                }
            }
            out.flush();
            out.close();
            in.close();
            if (cancel[0]) return;
            // 校验阶段：条子拉满、文案换掉，用户看得见「下完了、正在检查」这一步（不是凭空消失）
            stage = DlProg.VERIFY;
            pct = 100;
            line = a.getString(R.string.update_verifying);
            if (total > 0 && got != total) throw new Exception("下载不完整（" + got + "/" + total + " 字节）");
            if (!looksLikeApk(f)) throw new Exception("文件不完整，缺少安装清单");
        } finally {
            if (c != null) try { c.disconnect(); } catch (Exception ignored) {}
        }
    }

    /**
     * 是不是一个完好的安装包：ZipFile 会读中央目录，被截断的包在这一步就露馅
     * （只看头两个字节 "PK" 不够 —— 半包照样以 PK 开头）。
     */
    private static boolean looksLikeApk(File f) {
        java.util.zip.ZipFile z = null;
        try {
            z = new java.util.zip.ZipFile(f);
            return z.getEntry("AndroidManifest.xml") != null;
        } catch (Exception e) {
            return false;
        } finally {
            try { if (z != null) z.close(); } catch (Exception ignored) {}
        }
    }

    private static void install(final Activity a, File f, Info info) {
        Intent it = new Intent(Intent.ACTION_VIEW);
        it.setDataAndType(Uri.parse("content://" + ApkProvider.auth(a) + "/" + "update.apk"),
                "application/vnd.android.package-archive");
        it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            a.startActivity(it);
        } catch (Exception e) {
            Toast.makeText(a, a.getString(R.string.update_install_fail, f.getAbsolutePath()),
                    Toast.LENGTH_LONG).show();
        }
    }

    // ---------- 前台轮询：进度显示 + 更灵敏的检查 ----------

    /** 页面实现它就能拿到「下载进度」和「查到新版本」的回调 */
    public interface Watch {
        /** pct < 0 = 大小未知；line = 一行说明 */
        void onTick(int pct, String line);
        /** 静默检查查到新版本（同一版本只回调一次） */
        void onFound(Info info);
    }

    private static android.os.Handler h;
    private static Runnable loop;
    private static java.lang.ref.WeakReference<Activity> ref;
    private static Watch wcb;
    private static int tick;
    private static int lastFoundCode = -1;

    /**
     * 页面 onResume 调它、onPause 调 {@link #stopWatch()}。
     * 每 400ms 回一次进度（有下载在跑时才有内容），每 60 秒静默查一次更新 ——
     * 「不够灵敏」就是这么来的：前台一直在问，不用退回桌面再进来。
     */
    public static void startWatch(Activity a, Watch w) {
        ref = new java.lang.ref.WeakReference<Activity>(a);
        wcb = w;
        tick = 0;
        if (h == null) h = new android.os.Handler(android.os.Looper.getMainLooper());
        if (loop != null) h.removeCallbacks(loop);
        loop = new Runnable() {
            @Override public void run() {
                Activity act = ref == null ? null : ref.get();
                if (act == null || act.isFinishing()) { stopWatch(); return; }
                if (wcb != null) wcb.onTick(pct, line);
                // 下载在跑、弹窗却不在眼前（用户换了页面 / 弹窗被系统收走）→ 自动重新挂出来。
                // 这也是「进度条坏了」的一类表现：不是没下载，而是根本没人显示它。
                // ⚠️ 必须比 progressHost：弹窗是挂在创建它那个页面的窗口上的，用户退回上一页时
                //    那个窗口已经不在前面，而 isShowing() 依旧返回 true —— 光看 isShowing 就会
                //    以为「已经显示着了」，用户屏幕上其实什么都没有。
                if (busy && !act.isFinishing()
                        && (progressDlg == null || progressHost != act || !progressDlg.isShowing())) {
                    showProgress(act);
                }
                if (tick++ % 150 == 0 && !busy) silentCheck(act);     // 150 × 400ms = 60 秒
                h.postDelayed(this, 400);
            }
        };
        h.post(loop);
        silentCheck(a);        // 一进页面就查一次（节流 60 秒由 silentCheck 自己管）
    }

    public static void stopWatch() {
        if (h != null && loop != null) h.removeCallbacks(loop);
        loop = null;
        ref = null;
        wcb = null;
    }

    /** 静默检查一次（节流 60 秒）：查到新版只回报，弹不弹窗由页面决定 */
    private static void silentCheck(Activity a) {
        final Prefs p = Prefs.of(a);
        if (!p.on(Prefs.K_UP_AUTO, true)) return;
        long now = System.currentTimeMillis();
        if (now - p.l(Prefs.K_UP_LAST, 0) < 60L * 1000) return;
        p.set(Prefs.K_UP_LAST, now);
        checkResAsync(a, new Cb2() {
            @Override public void onRes(Res r) {
                Info info = r.newer ? r.server : null;
                if (info == null) { if (r.err == null) latest = null; return; }
                latest = info;
                if (info.code == lastFoundCode) return;        // 同一个版本不反复打扰
                if (!info.force && p.i(Prefs.K_UP_SEEN, 0) == info.code) {
                    if (wcb != null) wcb.onFound(info);        // 提醒过了：首页横幅仍然亮着
                    return;
                }
                p.set(Prefs.K_UP_SEEN, info.code);
                lastFoundCode = info.code;
                if (wcb != null) wcb.onFound(info);
            }
        });
    }

}

package com.kugoulite.plus

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule

// UI 层：广告视图出生拦截 + 弹窗/广告 Activity 阻止
@Suppress("DiscouragedPrivateApi")
object UiHook {

    // 3.4.9 已知广告视图类
    private val AD_CLASSES = setOf(
        "com.kugou.android.app.boot.gdt.GdtAdFrame",
        "com.kugou.android.app.player.view.PlayerAdMaskView",
        "com.kugou.android.app.player.comment.views.CmtDynamicAdsView",
    )

    private fun isAdView(v: View): Boolean {
        val cn = v.javaClass.name
        if (cn in AD_CLASSES) return true
        if (cn.startsWith("com.kugou.android.app.ads.views.")) return true
        if (cn.startsWith("com.kugou.android.app.player.") &&
            (cn.endsWith("FullAdView") || cn.endsWith("VideoAdsView") || cn.endsWith("PlayerAdMaskView"))
        ) return true
        return false
    }

    private val BLOCK_KEYWORDS = arrayOf(
        "签到", "立即领取", "去领取", "马上领取", "一键签到",
        "领取会员", "领取VIP", "领VIP", "听歌会员", "概念版VIP",
        "免费领取", "点击领取", "每日签到", "每日领取", "每日福利",
        "会员福利", "免费听歌", "立即开通", "去使用", "免费VIP",
        "赠送", "福利", "活动", "receive_vip", "listen_song",
        "充值", "续费", "抱走VIP", "免费抱走", "开启本周红包", "本周红包", "限定好运"
    )

    private val ACTIVITY_CLASS_KW = arrayOf(
        "Sign", "Vip", "Welfare", "Receive", "Recharge",
        "WebActivity", "H5Activity", "BrowserActivity", "AdActivity",
        "FeeV4VideoActivity", "RewardvideoPortraitADActivity",
        "RewardvideoLandscapeADActivity", "qq.e.tg.",
        "TTFullScreenVideoActivity", "TTRewardVideoActivity",
        "BackupAdActivity", "FeeInterceptWebActivity"
    )

    private val ACTIVITY_URL_KW = arrayOf(
        "getvips", "receive_vip", "signin", "sign_in",
        "welfare", "listen_song", "recharge"
    )

    @Volatile private var installed = false
    @Volatile private var hidden = 0

    fun install(xposed: XposedModule, cl: ClassLoader) {
        if (installed) return
        installed = true
        hookAdViews(xposed)
        hookDialogShow(xposed)
        hookWmAddView(xposed, cl)
        hookActivityStart(xposed)
        hookPopupWindow(xposed, cl)
    }

    // 广告视图出生拦截

    private fun hookAdViews(xposed: XposedModule) {
        try {
            val attach = Reflector.findMethod(View::class.java, "onAttachedToWindow")
            if (attach != null) {
                xposed.hook(attach)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        chain.proceed()
                        val v = chain.thisObject as? View
                        if (v != null && isAdView(v)) {
                            try {
                                v.visibility = View.GONE
                                if (hidden < 10) { hidden++; ModuleLog.log("广告视图隐藏: ${v.javaClass.name}") }
                            } catch (_: Throwable) {}
                        }
                    }
            }
        } catch (_: Throwable) {}
        ModuleLog.log("广告视图拦截安装完成")
    }

    // Dialog.show 阻止

    private fun hookDialogShow(xposed: XposedModule) {
        try {
            val show = Reflector.findMethod(Dialog::class.java, "show") ?: return
            xposed.hook(show)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val dlg = chain.thisObject as? Dialog ?: return@intercept chain.proceed()
                    // show 前 decorView 已存在时，一次判定就能拦住
                    val ready = dlg.window?.decorView != null
                    if (ready && isClaimDialog(dlg)) return@intercept null
                    chain.proceed()
                    // 兜底：decorView 在 show 时才建好、内容后填的弹窗，关掉再隐藏
                    if (!ready && isClaimDialog(dlg)) {
                        if (hidden < 10) { hidden++; ModuleLog.log("弹窗强制关闭: ${dlg.javaClass.name}") }
                        closeQuietly(dlg)
                    }
                    null
                }
        } catch (_: Exception) {}
    }

    private fun isClaimDialog(dlg: Dialog): Boolean =
        try { hasKeyword(dlg.window?.decorView, BLOCK_KEYWORDS) } catch (_: Exception) { false }

    private fun closeQuietly(dlg: Dialog) {
        try { if (dlg.isShowing) dlg.dismiss() } catch (_: Throwable) {}
        try { dlg.window?.decorView?.visibility = View.GONE } catch (_: Throwable) {}
    }

    // WindowManagerImpl.addView 底层拦截

    @SuppressLint("PrivateApi")
    private fun hookWmAddView(xposed: XposedModule, cl: ClassLoader) {
        try {
            val wm = Class.forName("android.view.WindowManagerImpl", false, cl)
            val add = Reflector.findMethod(wm, "addView", View::class.java, ViewGroup.LayoutParams::class.java) ?: return
            xposed.hook(add)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val v = chain.getArg(0) as? View
                    if (v != null && hasKeyword(v, BLOCK_KEYWORDS)) return@intercept null
                    chain.proceed()
                }
        } catch (_: Exception) {}
    }

    // Activity 启动链拦截

    private fun shouldBlock(intent: Intent?): Boolean {
        val i = intent ?: return false
        return try {
            val cls = i.component?.className
            if (cls != null && ACTIVITY_CLASS_KW.any { cls.contains(it) }) return true
            i.data?.toString()?.lowercase()?.let { url ->
                if (ACTIVITY_URL_KW.any { url.contains(it) }) return true
            }
            false
        } catch (_: Exception) { false }
    }

    private fun hookActivityStart(xposed: XposedModule) {
        var n = 0
        for (m in listOf(
            Reflector.findMethod(Activity::class.java, "startActivity", Intent::class.java),
            Reflector.findMethod(Activity::class.java, "startActivity", Intent::class.java, android.os.Bundle::class.java)
        )) {
            if (m == null) continue
            xposed.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain -> if (shouldBlock(chain.getArg(0) as? Intent)) null else chain.proceed() }
            n++
        }
        try {
            for (m in Class.forName("android.app.Instrumentation", false, Activity::class.java.classLoader).declaredMethods) {
                if (m.name != "execStartActivity") continue
                val idx = m.parameterTypes.indexOfFirst { it == Intent::class.java }
                if (idx < 0) continue
                xposed.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept { chain -> if (shouldBlock(chain.getArg(idx) as? Intent)) null else chain.proceed() }
                n++; break
            }
        } catch (_: Exception) {}
        ModuleLog.log("Activity 启动拦截: $n 个点")
    }

    // PopupWindow 阻止

    private fun hookPopupWindow(xposed: XposedModule, cl: ClassLoader) {
        try {
            val pw = Class.forName("android.widget.PopupWindow", false, cl)
            val hooker = XposedInterface.Hooker { chain ->
                val blocked = try { hasKeyword((chain.thisObject as? android.widget.PopupWindow)?.contentView, BLOCK_KEYWORDS) } catch (_: Exception) { false }
                if (blocked) null else chain.proceed()
            }
            for (m in listOf(
                Reflector.findMethod(pw, "showAtLocation", View::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType),
                Reflector.findMethod(pw, "showAsDropDown", View::class.java),
                Reflector.findMethod(pw, "showAsDropDown", View::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType),
                Reflector.findMethod(pw, "showAsDropDown", View::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            )) {
                if (m != null) xposed.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(hooker)
            }
        } catch (_: Exception) {}
    }

    // 关键词检查

    private fun hasKeyword(view: View?, kw: Array<String>): Boolean {
        if (view == null || view.visibility != View.VISIBLE) return false
        if (view is android.widget.TextView) {
            view.text?.let { t -> if (kw.any { t.contains(it) }) return true }
            view.hint?.let { h -> if (kw.any { h.contains(it) }) return true }
        }
        view.contentDescription?.let { d -> if (kw.any { d.contains(it) }) return true }
        if (view is ViewGroup) for (i in 0 until view.childCount) if (hasKeyword(view.getChildAt(i), kw)) return true
        return false
    }
}

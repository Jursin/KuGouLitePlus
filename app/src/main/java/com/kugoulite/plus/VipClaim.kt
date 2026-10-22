package com.kugoulite.plus

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast

// 领取编排：等登录 → 查任务状态 → 未领取则上报听歌 → 复查到账，未到账再走领取
object VipClaim {

    private const val MAX_RETRY = 3
    private const val RETRY_DELAY = 3_000L

    private val main = Handler(Looper.getMainLooper())
    private var retries = 0

    fun start(ctx: Context, cl: ClassLoader) {
        val proc = try {
            if (android.os.Build.VERSION.SDK_INT >= 28) android.app.Application.getProcessName()
            else ctx.applicationInfo.processName ?: ""
        } catch (_: Throwable) { "" }
        if (proc != "com.kugou.android.lite") return
        ModuleLog.log("VipClaim 启动")
        main.postDelayed({ awaitLogin(ctx, cl) }, 3000)
    }

    // 未登录不发任何请求，本地轮询直到登录后再开始领取
    private fun awaitLogin(ctx: Context, cl: ClassLoader) {
        if (KugouApi.isClaimedToday(ctx)) {
            ModuleLog.log("本地标记今日已领取，跳过")
            return
        }
        if (!KugouApi.isLoggedIn(cl)) {
            main.postDelayed({ awaitLogin(ctx, cl) }, 5000)
            return
        }
        Thread { claim(ctx, cl) }.start()
    }

    private fun claim(ctx: Context, cl: ClassLoader) {
        try {
            if (KugouApi.claimedOnServer(cl)) {
                ModuleLog.log("服务器已领取，补写本地标记")
                KugouApi.markClaimed(ctx)
                return
            }

            Thread.sleep(1500)
            val rep = KugouApi.reportListenSong(cl)
            ModuleLog.log("上报听歌: ${rep.describe()}")
            if (!rep.ok) return retry(ctx, cl, "上报失败")

            Thread.sleep(2500)
            if (!KugouApi.claimedOnServer(cl)) {
                val r = KugouApi.claimVip(cl)
                ModuleLog.log("领取: ${r.describe()}")
                if (!r.ok && r.code != KugouApi.EC_ALREADY) return retry(ctx, cl, "领取失败")
            }

            KugouApi.markClaimed(ctx)
            // Toast 只能在主线程弹
            main.post { Toast.makeText(ctx, "已领取今日会员", Toast.LENGTH_SHORT).show() }
        } catch (e: Exception) {
            ModuleLog.log("领取异常: $e")
            retry(ctx, cl, "领取异常")
        }
    }

    private fun retry(ctx: Context, cl: ClassLoader, reason: String) {
        if (retries++ >= MAX_RETRY) return ModuleLog.log("$reason，重试 $MAX_RETRY 次仍失败，本次启动放弃")
        ModuleLog.log("$reason，3 秒后重试 (第 $retries 次)")
        main.postDelayed({ awaitLogin(ctx, cl) }, RETRY_DELAY)
    }
}

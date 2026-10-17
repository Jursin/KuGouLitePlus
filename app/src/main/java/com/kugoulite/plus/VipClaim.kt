package com.kugoulite.plus

import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean

// 领取编排：登录态就绪 → 查任务状态 → 未领取才上报听歌并领取
object VipClaim {

    private val lock = AtomicBoolean(false)
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var loginRetries = 0
    private var claimRetries = 0

    fun start(ctx: Context, cl: ClassLoader) {
        val proc = try {
            if (android.os.Build.VERSION.SDK_INT >= 28) android.app.Application.getProcessName()
            else ctx.applicationInfo.processName ?: ""
        } catch (_: Throwable) { "" }
        if (proc != "com.kugou.android.lite") return
        ModuleLog.log("VipClaim 启动")
        handler.postDelayed({ attempt(ctx, cl) }, 3000)
    }

    private fun attempt(ctx: Context, cl: ClassLoader) {
        if (!KugouApi.isLoggedIn(cl)) {
            if (loginRetries++ < 6) {
                ModuleLog.log("未登录，20 秒后重试")
                handler.postDelayed({ attempt(ctx, cl) }, 20_000)
            } else ModuleLog.log("未登录，放弃")
            return
        }
        if (KugouApi.isClaimedToday(ctx)) {
            ModuleLog.log("本地标记今日已领取，跳过")
            return
        }
        if (!lock.compareAndSet(false, true)) return

        Thread {
            try {
                if (KugouApi.claimedOnServer(cl)) {
                    ModuleLog.log("今日已领取，跳过")
                    KugouApi.markClaimed(ctx)
                    return@Thread
                }

                Thread.sleep(1500)
                ModuleLog.log("上报听歌: ${KugouApi.reportListenSong(cl).describe()}")

                Thread.sleep(2500)
                val r = KugouApi.claimVip(cl)
                ModuleLog.log("领取: ${r.describe()}")
                when {
                    r.ok || r.code == KugouApi.EC_ALREADY -> KugouApi.markClaimed(ctx)
                    claimRetries++ < 3 -> {
                        ModuleLog.log("10 分钟后重试领取 (第 $claimRetries 次)")
                        handler.postDelayed({ attempt(ctx, cl) }, 600_000)
                    }
                }
            } catch (e: Exception) {
                ModuleLog.log("领取异常: $e")
            } finally {
                lock.set(false)
            }
        }.start()
    }
}

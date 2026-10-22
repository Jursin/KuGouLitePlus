package com.kugoulite.plus

import android.content.Context
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

class MainHook : XposedModule() {

    @Volatile
    private var installed = false

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        ModuleLog.setModule(this)
        log(Log.INFO, TAG, "模块已加载, 进程: ${param.processName}")
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        if (param.packageName != TARGET || installed) return
        installed = true

        @Suppress("NewApi")
        val cl = param.defaultClassLoader

        NetHook.install(this, cl)
        UiHook.install(this, cl)
        IntegrityBypass.install(this)
        hookAppCreate(cl)
    }

    private fun hookAppCreate(cl: ClassLoader) {
        try {
            val m = android.app.Application::class.java.getDeclaredMethod("onCreate")
            hook(m)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val r = chain.proceed()
                    try {
                        val ctx = chain.thisObject as Context
                        ModuleLog.init(ctx)
                        UiHook.installAdsCenter(this@MainHook, cl)
                        VipClaim.start(ctx, cl)
                    } catch (e: Exception) {
                        ModuleLog.log("启动失败: $e")
                    }
                    r
                }
        } catch (e: Exception) {
            ModuleLog.log("onCreate Hook 失败: $e")
        }
    }

    companion object {
        private const val TAG = "[KuGouLiteVip]"
        private const val TARGET = "com.kugou.android.lite"
    }
}

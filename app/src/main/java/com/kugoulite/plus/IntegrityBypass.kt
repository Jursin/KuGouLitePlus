package com.kugoulite.plus

import android.annotation.SuppressLint
import android.content.pm.PackageInfo
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule

// 防检测：Hook getPackageInfo 系列，自包签名以首见值为准，防止多次查询不一致触发自校验
@Suppress("DiscouragedPrivateApi")
object IntegrityBypass {

    private const val TARGET = "com.kugou.android.lite"
    private const val PM = "android.app.ApplicationPackageManager"
    private const val FLAGS = $$"android.content.pm.PackageManager$PackageInfoFlags"

    @Volatile private var cached: Array<android.content.pm.Signature>? = null

    fun install(xposed: XposedModule) {
        var n = 0
        for (pt in paramTypes()) {
            n += hookGpi(xposed, "getPackageInfo", pt)
            n += hookGpi(xposed, "getCurrentPackageInfo", pt)
        }
        n += hookInstalled(xposed)
        ModuleLog.log("防检测安装: $n 个重载")
    }

    private fun paramTypes() = listOfNotNull(
        arrayOf<Class<*>>(String::class.java, Int::class.javaPrimitiveType!!),
        try { arrayOf<Class<*>>(String::class.java, Class.forName(FLAGS)) } catch (_: Throwable) { null }
    )

    @SuppressLint("PrivateApi")
    private fun hookGpi(xposed: XposedModule, name: String, pt: Array<Class<*>>): Int {
        return try {
            val m = Reflector.findMethod(Class.forName(PM), name, *pt) ?: return 0
            xposed.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain -> val r = chain.proceed(); stabilize(r); r }
            1
        } catch (_: Exception) { 0 }
    }

    @SuppressLint("PrivateApi")
    private fun hookInstalled(xposed: XposedModule): Int {
        return try {
            var ok = 0
            for (m in Class.forName(PM).declaredMethods.filter { it.name == "getInstalledPackages" }) {
                xposed.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        val r = chain.proceed()
                        try {
                            val list: List<*>? = when (r) {
                                is List<*> -> r
                                else -> try { r?.javaClass?.getMethod("getList")?.invoke(r) as? List<*> } catch (_: Throwable) { null }
                            }
                            list?.forEach { stabilize(it) }
                        } catch (_: Exception) {}
                        r
                    }
                ok++
            }
            ok
        } catch (_: Exception) { 0 }
    }

    @Suppress("DEPRECATION")
    private fun stabilize(result: Any?) {
        val pi = result as? PackageInfo ?: return
        if (pi.packageName != TARGET) return
        val sigs = pi.signatures ?: return
        if (sigs.isEmpty()) return
        val c = cached
        if (c == null) { cached = sigs.copyOf(); return }
        if (c.size != sigs.size) { cached = sigs.copyOf(); return }
        for (i in c.indices) if (sigs[i] != c[i]) sigs[i] = c[i]
    }
}

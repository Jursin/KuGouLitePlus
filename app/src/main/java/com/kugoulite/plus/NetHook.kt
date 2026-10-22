package com.kugoulite.plus

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.io.ByteArrayInputStream
import java.lang.reflect.Proxy
import java.net.InetAddress

// 广告网络拦截：GDT SDK 返回空响应 + DNS 兜底
object NetHook {

    private val AD_HOSTS = arrayOf(
        "gdt.qq.com", "gdtimg.com", "adsmind",
        "e.qq.com", "gtimg.cn", "l.qq.com",
        "pangle.io", "pangle.cn", "openadsdk",
        "pglstatp-toutiao.com", "pangolin-sdk-toutiao.com",
        "toutiao.com", "toutiaoimg.com", "snssdk.com", "ctobsnssdk.com",
        "ads.service.kugou.com", // 酷狗自家广告上报
    )

    private fun isAd(url: String) = AD_HOSTS.any { url.contains(it, ignoreCase = true) }

    @Volatile private var installed = false
    @Volatile private var blocked = 0

    fun install(xposed: XposedModule, cl: ClassLoader) {
        if (installed) return
        installed = true
        hookGdt(xposed, cl)
        hookDns(xposed)
    }

    // GDT SDK 广告拦截（GDT 不走宿主网络栈）

    private fun hookGdt(xposed: XposedModule, cl: ClassLoader) {
        try {
            val clientCls = Class.forName("com.qq.e.comm.net.NetworkClientImpl", false, cl)
            val reqCls = Class.forName("com.qq.e.comm.net.rr.Request", false, cl)
            val respCls = Class.forName("com.qq.e.comm.net.rr.Response", false, cl)
            val cbCls = Class.forName("com.qq.e.comm.net.NetworkCallBack", false, cl)
            val futureCls = Class.forName("java.util.concurrent.Future", false, cl)

            if (fakeResp(respCls) == null) { ModuleLog.log("GDT rr.Response 代理构造失败，跳过"); return }

            val getUrl = try { reqCls.getMethod("getUrlWithParas") } catch (_: Throwable) { reqCls.getMethod("getUrl") }
            val getUrl2 = try { reqCls.getMethod("getUrl") } catch (_: Throwable) { null }
            val onResponse = cbCls.getMethod("onResponse", reqCls, respCls)

            for (m in clientCls.declaredMethods.filter { it.name == "submit" }) {
                val returnsFuture = m.returnType != Void.TYPE
                xposed.hook(m)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        val req = chain.getArg(0) ?: return@intercept chain.proceed()
                        val url = try { (getUrl.invoke(req) ?: getUrl2?.invoke(req))?.toString() } catch (_: Throwable) { null }
                        if (url == null || !isAd(url)) return@intercept chain.proceed()
                        val resp = fakeResp(respCls) ?: return@intercept chain.proceed()
                        for (i in 1 until m.parameterTypes.size) {
                            val a = chain.getArg(i)
                            if (a != null && cbCls.isAssignableFrom(a.javaClass)) {
                                try { onResponse.invoke(a, req, resp) } catch (_: Throwable) {}
                                break
                            }
                        }
                        if (blocked < 10) { blocked++; ModuleLog.log("GDT广告拦截: ${url.take(80)}") }
                        if (returnsFuture) fakeFuture(futureCls, resp) else null
                    }
            }

            // TaskCallable.call：任务层拦截（零流量）
            try {
                val tcCls = Class.forName($$"com.qq.e.comm.net.NetworkClientImpl$TaskCallable", false, cl)
                val call = tcCls.declaredMethods.firstOrNull { it.name == "call" && it.parameterTypes.isEmpty() }
                if (call != null) {
                    val reqFields = tcCls.declaredFields.filter { reqCls.isAssignableFrom(it.type) || it.type.isAssignableFrom(reqCls) }
                    reqFields.forEach { it.isAccessible = true }
                    xposed.hook(call)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept { chain ->
                            var url: String? = null
                            for (f in reqFields) {
                                val req = try { f.get(chain.thisObject) } catch (_: Throwable) { null } ?: continue
                                url = try { getUrl.invoke(req)?.toString() } catch (_: Throwable) { null }
                                if (url != null) break
                            }
                            if (url == null || !isAd(url)) return@intercept chain.proceed()
                            val resp = fakeResp(respCls) ?: return@intercept chain.proceed()
                            if (blocked < 10) { blocked++; ModuleLog.log("GDT任务层拦截: ${url.take(80)}") }
                            resp
                        }
                }
            } catch (t: Throwable) { ModuleLog.log("GDT TaskCallable Hook 失败: $t") }

            // initResponse：HTTP 响应注入点
            for (impl in listOf("com.qq.e.comm.net.rr.PlainRequest", "com.qq.e.comm.net.rr.S2SSRequest")) {
                try {
                    val cls = Class.forName(impl, false, cl)
                    val m = cls.getDeclaredMethod("initResponse", java.net.HttpURLConnection::class.java)
                    xposed.hook(m)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept { chain ->
                            val req = chain.thisObject ?: return@intercept chain.proceed()
                            val url = try { getUrl.invoke(req)?.toString() } catch (_: Throwable) { null }
                            if (url == null || !isAd(url)) return@intercept chain.proceed()
                            val resp = fakeResp(respCls) ?: return@intercept chain.proceed()
                            if (blocked < 10) { blocked++; ModuleLog.log("GDT initResponse 拦截: ${url.take(80)}") }
                            resp
                        }
                } catch (_: Throwable) {}
            }

            ModuleLog.log("GDT 广告拦截安装成功")
        } catch (t: Throwable) {
            ModuleLog.log("GDT 广告拦截失败: $t")
        }
    }

    // DNS 兜底

    private fun hookDns(xposed: XposedModule) {
        try {
            for (m in listOf(
                InetAddress::class.java.getDeclaredMethod("getByName", String::class.java),
                InetAddress::class.java.getDeclaredMethod("getAllByName", String::class.java)
            )) {
                xposed.hook(m)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        val host = chain.getArg(0) as? String
                        if (host != null && isAd(host))
                            return@intercept InetAddress.getByAddress(host, byteArrayOf(0, 0, 0, 0))
                        chain.proceed()
                    }
            }
            ModuleLog.log("DNS 广告拦截安装成功")
        } catch (t: Throwable) { ModuleLog.log("DNS 拦截失败: $t") }
    }

    // 构造合成响应

    private fun fakeResp(respCls: Class<*>): Any? = try {
        val payload = "{}"
        val bytes = payload.toByteArray()
        Proxy.newProxyInstance(respCls.classLoader, arrayOf(respCls)) { _, m, _ ->
            when (m.name) {
                "getStatusCode" -> 200
                "getStringContent" -> payload
                "getBytesContent" -> bytes
                "getStreamContent" -> ByteArrayInputStream(bytes)
                "toString" -> "GdtFake(200,{})"
                else -> when (m.returnType) {
                    Integer.TYPE -> 0
                    java.lang.Boolean.TYPE -> false
                    java.lang.Long.TYPE -> 0L
                    else -> null
                }
            }
        }
    } catch (_: Throwable) { null }

    private fun fakeFuture(futureCls: Class<*>, response: Any): Any? = try {
        Proxy.newProxyInstance(futureCls.classLoader, arrayOf(futureCls)) { _, m, _ ->
            when (m.name) {
                "get" -> response; "cancel" -> false
                "isCancelled" -> false; "isDone" -> true
                else -> when (m.returnType) {
                    Integer.TYPE -> 0
                    java.lang.Boolean.TYPE -> false
                    java.lang.Long.TYPE -> 0L
                    else -> null
                }
            }
        }
    } catch (_: Throwable) { null }
}

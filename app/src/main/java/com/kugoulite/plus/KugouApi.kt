package com.kugoulite.plus

import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

// 酷狗接口调用：参数与签名委托宿主 com.kugou.common.network.w，登录态与设备信息自动注入
object KugouApi {

    private const val BASE = "https://gateway.kugou.com"
    private const val MODULE = "ReportPlaySongToServerProtocol"
    private const val MIX_SONG_ID = 666075191L

    const val EC_ALREADY = 131001

    // ok = status==1，code = 服务端 error_code
    class Result(val ok: Boolean, val code: Int, val message: String) {
        fun describe(): String {
            if (ok) return "成功"
            val hint = when (code) {
                EC_ALREADY -> "今日已领取"
                130011 -> "今日无听歌记录"
                20028 -> "账号风控，需手动领取"
                else -> ""
            }
            return "ec=$code " + message.ifEmpty { hint.ifEmpty { "无错误描述" } }
        }
    }

    private fun params(cl: ClassLoader, extra: Map<String, String>, body: String): LinkedHashMap<String, String>? = try {
        val m = Reflector.findMethod(
            Class.forName("com.kugou.common.network.w", false, cl), "b", Map::class.java, String::class.java
        ) ?: return null
        LinkedHashMap(extra).also { m.invoke(null, it, body) }
    } catch (e: Exception) {
        ModuleLog.log("参数构造失败: $e")
        null
    }

    fun isLoggedIn(cl: ClassLoader): Boolean {
        val p = params(cl, emptyMap(), "") ?: return false
        return !p["token"].isNullOrEmpty() && (p["userid"] ?: "0") != "0"
    }

    // 听歌任务状态：3 = 今日已领取，0/1/2 = 未领取，-1 = 任务不存在
    fun claimedOnServer(cl: ClassLoader): Boolean {
        val p = params(cl, mapOf("get_stats" to "1"), "") ?: return false
        val resp = request("GET", "$BASE/youth/v1/activity/get_listen_song_task", p, null) ?: return false
        return try {
            val j = JSONObject(resp)
            val data = j.optJSONObject("data")
            when {
                j.optInt("status") != 1 -> {
                    ModuleLog.log("任务状态异常: status=${j.optInt("status")} ec=${j.optInt("error_code")} msg=${j.optString("error_msg")}")
                    false
                }
                data?.has("task_stat") != true -> {
                    ModuleLog.log("任务状态: 无 task_stat, data=${j.optString("data").take(200)}")
                    false
                }
                else -> {
                    val stat = data.optInt("task_stat")
                    ModuleLog.log("任务状态: task_stat=$stat, receive_total=${data.optInt("receive_total")}")
                    stat == 3
                }
            }
        } catch (e: Exception) {
            ModuleLog.log("任务状态解析失败: $e")
            false
        }
    }

    fun claimVip(cl: ClassLoader) = post(cl, "/youth/v1/recharge/receive_vip_listen_song", null)

    fun reportListenSong(cl: ClassLoader) = post(cl, "/youth/v2/report/listen_song", "{\"mixsongid\":$MIX_SONG_ID}")

    private fun post(cl: ClassLoader, path: String, body: String?): Result {
        val p = params(cl, emptyMap(), body ?: "") ?: return Result(false, -1, "参数构造失败")
        val resp = request("POST", BASE + path, p, body) ?: return Result(false, -1, "网络请求失败")
        return try {
            val j = JSONObject(resp)
            Result(j.optInt("status") == 1, j.optInt("error_code"), j.optString("error_msg"))
        } catch (_: Exception) {
            Result(false, -1, "解析失败: ${resp.take(160)}")
        }
    }

    // 请求与签名，失败重试一次
    private fun request(method: String, url: String, p: Map<String, String>, body: String?): String? {
        val full = buildString {
            append(url).append('?')
            for ((k, v) in p) append(enc(k)).append('=').append(enc(v)).append('&')
        }.trimEnd('&')
        val ua = "Android${Build.VERSION.RELEASE.replace(".", "")}-${p["clientver"]}-${p["appid"]}-0-$MODULE"
        repeat(2) { attempt ->
            var conn: HttpURLConnection? = null
            try {
                val c = URL(full).openConnection() as HttpURLConnection
                conn = c
                c.requestMethod = method
                c.connectTimeout = 10000
                c.readTimeout = 15000
                c.setRequestProperty("User-Agent", ua)
                p["token"]?.takeIf { it.isNotEmpty() }?.let {
                    c.setRequestProperty("Cookie", "token=$it; userid=${p["userid"]}; dfid=${p["dfid"]}; KUGOU_API_MID=${p["mid"]}")
                }
                if (body != null) {
                    c.doOutput = true
                    c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    c.outputStream.use { it.write(body.toByteArray()) }
                }
                val stream = if (c.responseCode in 200..299) c.inputStream else c.errorStream ?: return null
                return BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
            } catch (e: Exception) {
                if (attempt == 1) ModuleLog.log("请求失败: $e")
            } finally {
                conn?.disconnect()
            }
            if (attempt == 0) Thread.sleep(2000)
        }
        return null
    }

    private fun enc(s: String) = try { URLEncoder.encode(s, "UTF-8") } catch (_: Exception) { s }

    private fun day(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("Asia/Shanghai")
        return sdf.format(Date())
    }

    // 本地领取标记

    private const val SP = "kugoulite_vip_module"
    private const val KEY_CLAIMED = "last_claim_success_date"

    fun isClaimedToday(ctx: Context) =
        day() == ctx.getSharedPreferences(SP, Context.MODE_PRIVATE).getString(KEY_CLAIMED, "")

    fun markClaimed(ctx: Context) {
        ctx.getSharedPreferences(SP, Context.MODE_PRIVATE).edit().putString(KEY_CLAIMED, day()).apply()
    }
}

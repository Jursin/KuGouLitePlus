package com.kugoulite.plus

import android.content.Context
import android.util.Log
import io.github.libxposed.api.XposedModule
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ModuleLog {
    private const val TAG = "[KuGouLiteVip]"
    private const val MAX_BYTES = 1024L * 1024L

    @Volatile private var module: XposedModule? = null
    @Volatile private var logFile: String? = null

    private val timeFmt = ThreadLocal.withInitial { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }
    private val lock = Any()
    private var writer: BufferedWriter? = null
    private var written = 0L

    @JvmStatic
    fun setModule(m: XposedModule) { module = m }

    fun init(ctx: Context) {
        try {
            logFile = ctx.filesDir.absolutePath + "/kugou_vip_module.log"
            val f = File(logFile!!)
            f.parentFile?.mkdirs()
            if (f.exists() && f.length() > MAX_BYTES) f.delete()
        } catch (_: Exception) {
            logFile = null
        }
    }

    fun log(msg: String) {
        val line = "[" + timeFmt.get()!!.format(Date()) + "] " + msg
        val m = module
        if (m != null) {
            try { m.log(Log.INFO, TAG, line) } catch (_: Throwable) {}
        } else {
            Log.i(TAG, line)
        }
        val path = logFile ?: return
        try {
            synchronized(lock) {
                if (writer == null || written > MAX_BYTES) reopen(path)
                writer?.let { it.write(line); it.write("\n"); it.flush(); written += line.length + 1 }
            }
        } catch (_: Exception) {}
    }

    private fun reopen(path: String) {
        try { writer?.flush(); writer?.close() } catch (_: Exception) {}
        writer = null
        try {
            val file = File(path)
            file.parentFile?.mkdirs()
            if (file.length() > MAX_BYTES) file.delete()
            written = if (file.exists()) file.length() else 0L
            writer = BufferedWriter(OutputStreamWriter(FileOutputStream(file, true), Charsets.UTF_8), 8192)
        } catch (_: Exception) {}
    }
}

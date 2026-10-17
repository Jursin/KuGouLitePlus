package com.kugoulite.plus

import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

object Reflector {
    private val cache = ConcurrentHashMap<String, Method>()

    fun findMethod(clazz: Class<*>, name: String, vararg paramTypes: Class<*>?): Method? {
        val key = clazz.name + "#" + name + "#" + paramTypes.joinToString(",") { it?.name ?: "null" }
        cache[key]?.let { return it }
        val m = try { clazz.getDeclaredMethod(name, *paramTypes) }
        catch (_: NoSuchMethodException) {
            try { clazz.getMethod(name, *paramTypes) }
            catch (_: NoSuchMethodException) { null }
        }
        if (m != null) cache[key] = m
        return m
    }
}

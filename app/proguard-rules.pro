# Xposed 入口类（java_init.list 按类名字符串加载）
-keep class com.kugoulite.plus.MainHook { *; }

# libxposed 是 compileOnly，R8 看不到实现
-dontwarn io.github.libxposed.**

-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

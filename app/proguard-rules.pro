# Shizuku talks to a transparent proxy: keep its API and any hidden classes it
# resolves reflectively, otherwise the binder handshake fails in release builds.
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }
-dontwarn rikka.shizuku.**
-dontwarn moe.shizuku.**

# The catalog parser reads JSON by key name; nothing to keep, but keep the model
# class members' names stable so stack traces stay readable in issues.
-keepclassmembers class dev.cameleonnbss.originostoolkit.core.model.** { <fields>; }

# Compose
-dontwarn androidx.compose.**

# Crash reports: keep line numbers so bug reports are actionable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

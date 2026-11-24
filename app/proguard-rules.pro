# Island has no reflection-based serialization; the defaults from proguard-android-optimize.txt
# are sufficient. Keep manifest-declared components (AGP does this automatically).

# Keep line numbers so crash traces from personal release builds stay readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

-keep class rikka.shizuku.** { *; }
-dontwarn rikka.shizuku.**
-keep class org.lsposed.hiddenapibypass.** { *; }
-keep class com.arnav.island.search.ISearchAutoLaunchService** { *; }
-keep class com.arnav.island.search.ISearchAutoLaunchCallback** { *; }
-keep class com.arnav.island.search.SearchAutoLaunchUserService { *; }

# LocalDeX (Shizuku UserService + Wireless ADB / Conscrypt / X.509)
-keep class com.arnav.island.dex.IDexUserService** { *; }
-keep class com.arnav.island.dex.IDexUserCallback** { *; }
-keep class com.arnav.island.dex.DexShizukuUserService { *; }
-keep class io.github.muntashirakon.adb.** { *; }
-dontwarn io.github.muntashirakon.adb.**
-keep class org.conscrypt.** { *; }
-dontwarn org.conscrypt.**
-keep class android.sun.security.** { *; }
-dontwarn android.sun.security.**



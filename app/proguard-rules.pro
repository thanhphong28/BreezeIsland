# Island has no reflection-based serialization; the defaults from proguard-android-optimize.txt
# are sufficient. Keep manifest-declared components (AGP does this automatically).

# Keep line numbers so crash traces from personal release builds stay readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

-keep class rikka.shizuku.** { *; }
-dontwarn rikka.shizuku.**
-keep class com.arnav.island.soundisland.audio.VolumeSuppressor { *; }
-keep interface android.media.IVolumeController { *; }
-keep interface android.media.IAudioService { *; }


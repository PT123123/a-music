# Add project specific ProGuard rules here.

# Keep the JNI bridge (method names must match the C JNINativeMethod table).
-keep class com.amusic.player.MPVLib { *; }

# mpv client struct is passed opaquely; keep native-linked classes if you add any.
-keepclassmembers class * {
    native <methods>;
}

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# Coil
-dontwarn okio.**

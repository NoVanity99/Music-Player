# Media3 / ExoPlayer ship their own consumer rules; these are belt-and-braces.
-keep class androidx.media3.session.** { *; }
-dontwarn androidx.media3.**

# The MediaSessionService is referenced from the manifest (kept by AAPT), but keep
# its members too since Media3 calls into it reflectively-ish via binder.
-keep class com.novanity.musicplayer.playback.PlaybackService { *; }

# Room: entities/DAOs are generated; keep generated implementations.
-keep class * extends androidx.room.RoomDatabase { <init>(...); }
-keep @androidx.room.Entity class * { *; }
-dontwarn androidx.room.paging.**

# Kotlin coroutines
-dontwarn kotlinx.coroutines.**

# Guava (pulled in by Media3) references optional annotations.
-dontwarn com.google.errorprone.annotations.**
-dontwarn com.google.j2objc.annotations.**
-dontwarn org.checkerframework.**
-dontwarn javax.annotation.**
-dontwarn afu.org.checkerframework.**

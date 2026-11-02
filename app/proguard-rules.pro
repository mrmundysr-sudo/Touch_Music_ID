# Decorative slot art is looked up by name at runtime (see docs/VISUAL_ASSET_MANIFEST.md).
-keep class **.R$drawable { *; }

# Apple ShazamKit AAR (consumer rules are not applied for a local files() AAR, so they are repeated here).
-keepattributes Signature
-keep class com.google.gson.** { *; }
-keep class com.shazam.sigx.** { *; }
-keep class com.shazam.jni.** { *; }
-keep class com.shazam.sig.** { *; }
-keep class com.shazam.recognition.** { *; }
-keep class com.shazam.shazamkit.internal.** { *; }
-keepclassmembers class com.shazam.shazamkit.MediaItem { *; }
-dontwarn com.shazam.**

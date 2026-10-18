# Keep model classes for Gson
-keep class com.xrdavies.mob.data.** { *; }
-keepclassmembers class com.xrdavies.mob.data.** { *; }

# libwebrtc classes are reached from native code over JNI.
-keep class org.webrtc.** { *; }

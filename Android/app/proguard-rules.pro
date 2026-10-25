-keep class com.izzy2lost.neshd.NativeLib { *; }
-keepclassmembers class com.izzy2lost.neshd.NativeLib { *; }
-keep class com.izzy2lost.neshd.EmulatorActivity { *; }
-dontwarn kotlin.**

# RetroAchievements' HTTP: FindClass + GetStaticMethodID from native code
# (cpp/cheevos_session.cpp), so R8 sees no caller and would remove or rename it.
-keep class com.izzy2lost.neshd.CheevosHttp {
    public static byte[] perform(java.lang.String, java.lang.String, java.lang.String, java.lang.String);
}

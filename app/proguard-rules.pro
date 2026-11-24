# JNI entry points are looked up by name at runtime.
-keepclasseswithmembernames class com.example.voiceagent.engine.CactusNative {
    native <methods>;
}

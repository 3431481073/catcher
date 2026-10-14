# M0 无混淆规则

# sherpa-onnx：JNI 按类名/方法名从 C++ 侧反查，混淆后必然 NoSuchMethodError
-keep class com.k2fsa.sherpa.onnx.** { *; }

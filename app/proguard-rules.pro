# ML Kit / TFLite：保留 OCR 运行时反射用到的类
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_** { *; }
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.**

# 让崩溃栈可读（分发版本也保留行号，便于真机排错）
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

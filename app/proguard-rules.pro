# PythonHost and the models are used by name from Python (Chaquopy) and by
# kotlinx.serialization: keep the app's own classes as they are.
-keep class fr.zapette.tv.** { *; }
-keepclassmembers class fr.zapette.tv.** { *; }

# Chaquopy (Java <-> Python bridge, uses reflection)
-keep class com.chaquo.python.** { *; }
-dontwarn com.chaquo.python.**

# Keep line numbers in crash reports
-keepattributes SourceFile,LineNumberTable,*Annotation*,Signature,InnerClasses,EnclosingMethod

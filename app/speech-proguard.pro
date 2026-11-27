# JNA's reflective native bindings must retain their names. Based on the Vosk Android demo.
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { public *; }
-keep class org.vosk.** { *; }
# JNA ships optional desktop AWT signatures; Android does not provide java.awt.
-dontwarn java.awt.**

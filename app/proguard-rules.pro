# OpenFX ProGuard Rules

# Keep smbj classes (reflection-heavy)
-keep class com.hierynomus.** { *; }
-dontwarn com.hierynomus.**

# Keep JSch (SSH)
-keep class com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.**

# Keep Apache commons
-keep class org.apache.commons.** { *; }
-dontwarn org.apache.commons.**

# Kotlin serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.openfx.**$$serializer { *; }
-keepclassmembers class com.openfx.** {
    *** Companion;
}
-keepclasseswithmembers class com.openfx.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# SMB 共享清單 (dcerpc) 與 NTLM 用的 BouncyCastle — 都靠反射載入
-keep class com.rapid7.client.dcerpc.** { *; }
-dontwarn com.rapid7.client.dcerpc.**
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn org.slf4j.**
-dontwarn javax.el.**
-dontwarn org.ietf.jgss.**

# Rules for the production (release) build. R8 shrinks, optimizes and obfuscates.
# The libraries bring their own consumer rules (Hilt, OkHttp, CameraX, coroutines, DataStore,
# Compose). Below is what this app needs on top, or what is kept to be safe.

# Stack traces of a crash stay readable with the mapping file of the build
-keepattributes SourceFile,LineNumberTable,*Annotation*,Signature,InnerClasses,EnclosingMethod
-renamesourcefileattribute SourceFile

# Only named in the manifest (AAPT also generates rules for them, this is the belt and braces)
-keep class de.jobisoft.hitsterconnector.HitsterConnectorApplication { *; }
-keep class de.jobisoft.hitsterconnector.MainActivity { *; }
-keep class de.jobisoft.hitsterconnector.data.media.AmazonMusicListener { *; }

# Hilt / Dagger: generated components and ViewModels are found through annotations
-keep class dagger.hilt.** { *; }
-keep class * extends androidx.lifecycle.ViewModel { <init>(...); }
-keep @dagger.hilt.android.lifecycle.HiltViewModel class * { <init>(...); }
-keep @dagger.hilt.android.AndroidEntryPoint class * { *; }
-keep @dagger.hilt.InstallIn class * { *; }
-keep class **_HiltModules* { *; }
-keep class **_Factory { *; }
-keep class **_MembersInjector { *; }
-dontwarn dagger.hilt.internal.aggregatedroot.codegen.**

# Gson: it is provided by the app (NetworkModule), its models would be read by reflection
-keep class de.jobisoft.hitsterconnector.domain.model.** { *; }
-keepclassmembers,allowobfuscation class * { @com.google.gson.annotations.SerializedName <fields>; }
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# OkHttp / Okio: optional platforms are looked up by name
-dontwarn okhttp3.internal.platform.**
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

# ZXing (journeyapps zxing-android-embedded) and CommonMark
-dontwarn com.google.zxing.**
-keep class com.google.zxing.** { *; }
-keep class org.commonmark.** { *; }

# DataStore (protobuf lite inside preferences)
-keepclassmembers class * extends androidx.datastore.preferences.protobuf.GeneratedMessageLite { <fields>; }

# Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-dontwarn kotlinx.coroutines.debug.**

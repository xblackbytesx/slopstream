# kotlinx.serialization: keep generated serializers for navigation routes and stored settings.
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class com.xblackbytesx.slopstream.** {
    *** Companion;
}
-keepclasseswithmembers class com.xblackbytesx.slopstream.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# PoTokenWebView: methods called from JavaScript by name.
-keepclassmembers class com.xblackbytesx.slopstream.potoken.PoTokenWebView {
    @android.webkit.JavascriptInterface <methods>;
}

# NewPipeExtractor: Rhino evaluates YouTube's player JS reflectively, and protobuf-lite
# reads generated message fields by reflection. Both break when shrunk.
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-keep class com.google.protobuf.** { *; }
-keep class org.schabi.newpipe.extractor.** { *; }
-dontwarn org.mozilla.javascript.**
-dontwarn org.mozilla.classfile.**
-dontwarn java.beans.**
-dontwarn javax.script.**
-dontwarn jdk.dynalink.**
-dontwarn com.google.re2j.**
-dontwarn javax.annotation.**

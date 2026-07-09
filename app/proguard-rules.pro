# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class app.captions.** {
    *** Companion;
}
-keepclasseswithmembers class app.captions.** {
    kotlinx.serialization.KSerializer serializer(...);
}

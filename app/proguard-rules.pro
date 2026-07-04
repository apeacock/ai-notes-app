# Room entities
-keep class com.ai.notes.data.database.entities.** { *; }

# API request/response models
-keep class com.ai.notes.data.ai.model.** { *; }

# AppFunctions (prevent obfuscation of annotated methods)
-keepclassmembers class com.ai.notes.AppFunctions.** {
    @androidx.appfunctions.annotation.AppFunction *;
}

# Retrofit
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes Exceptions
-keepclassmembers class * {
    @retrofit2.http.* <methods>;
}

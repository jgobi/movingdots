# Strip Kotlin null-check intrinsics and debug logging in release.
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    public static void checkNotNull*(...);
    public static void checkParameterIsNotNull(...);
    public static void checkNotNullParameter(...);
    public static void checkNotNullExpressionValue(...);
    public static void checkExpressionValueIsNotNull(...);
}
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}

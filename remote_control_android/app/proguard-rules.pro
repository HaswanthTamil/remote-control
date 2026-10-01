# Keep BouncyCastle's JCE provider classes: Ed25519 is resolved reflectively
# through the provider on API < 33.
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
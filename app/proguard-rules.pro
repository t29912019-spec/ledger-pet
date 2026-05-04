# Ledger App ProGuard Rules
-keepattributes Signature
-keepattributes *Annotation*

# Room
-keep class com.example.ledger.data.model.** { *; }

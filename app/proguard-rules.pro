# PDFBox-Android loads classes/resources dynamically and references optional desktop classes.
-keep class com.tom_roush.pdfbox.** { *; }
-dontwarn com.tom_roush.**
-dontwarn org.apache.**
-dontwarn org.bouncycastle.**
-dontwarn com.gemalto.jp2.**
-dontwarn java.awt.**
-dontwarn javax.**
-dontwarn org.w3c.**
-dontwarn org.xml.**

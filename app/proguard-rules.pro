# Room / Kotlin のメタデータは保持する
-keepattributes *Annotation*, InnerClasses, Signature

# データクラス (DAO クエリのマッピング用)
-keep class com.tonbiattack.fastestpictureviewer.data.** { *; }
-keep class com.tonbiattack.fastestpictureviewer.repository.** { *; }

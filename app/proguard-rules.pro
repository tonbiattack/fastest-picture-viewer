# R8 の設定 (Issue #121)
#
# リリースビルドは isMinifyEnabled = true で難読化・縮小する。
# リフレクションや名前で解決する箇所を壊さないよう、必要なものだけ残す。
# 過剰に -keep すると縮小の意味が薄れるため、根拠のあるものだけを書く。

# Room / Kotlin のメタデータは保持する
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod

# 行番号は難読化しても残す。クラッシュ時のスタックトレースを読めるようにするため
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile

# データクラス (DAO クエリのマッピング用)
-keep class com.tonbiattack.fastestpictureviewer.data.** { *; }
-keep class com.tonbiattack.fastestpictureviewer.repository.** { *; }

# --- Room ---
# Room が生成する実装クラスはリフレクションで解決される
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep @androidx.room.Entity class * { *; }
-dontwarn androidx.room.paging.**

# --- Kotlin コルーチン ---
# 内部で ServiceLoader を使う箇所があるため警告を抑止する
-dontwarn kotlinx.coroutines.**

# --- Coil (画像読み込み) ---
# Coil は ImageLoader をリフレクションで初期化しないが、
# 難読化で壊れると画像が出なくなるため主要クラスを残す
-keep class coil.** { *; }
-dontwarn coil.**

# --- Compose ---
# Compose ランタイムは難読化に対応しているが、
# プレビュー用のツール類はリリースに含めない
-dontwarn androidx.compose.ui.tooling.**

# --- バックアップ JSON ---
# org.json を使った手書きの変換のため、追加の keep は不要。
# ただし BackupData などは data パッケージの keep で保護済み。

# --- デバッグ情報を残す範囲 ---
# 例外のスタックトレースから原因を追えるようにする
-keepnames class com.tonbiattack.fastestpictureviewer.** { *; }

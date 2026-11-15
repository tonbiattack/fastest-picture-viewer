package com.tonbiattack.fastestpictureviewer.data

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File

/**
 * DB を開けなかったときの復旧結果 (Issue #88)。
 * UI はこれを見て「データを作り直した」ことを案内する。
 */
data class DatabaseRecoveryResult(
    /** 復旧が必要と判断した理由 */
    val reason: DatabaseRecovery.Reason,
    /** 退避した DB のパス。退避できなかった場合は null */
    val quarantinedPath: String?
)

/**
 * アプリの(Room)データベース。
 * - images:                   MediaStore 走査結果のメタ情報 (単一の真実のソース)
 * - sync_state:               差分同期の時刻管理 (単一行)
 * - volume_sync_state:        ボリューム単位の MediaStore 世代番号管理
 * - favorite_images:          画像単位のお気に入り (本体画像に変更を加えない)
 * - favorite_folders:         フォルダ単位のお気に入り
 * - excluded_folders:         フォルダ一覧から除外するフォルダ (非破壊)
 * - folder_scroll_positions:  フォルダ別の画像一覧スクロール位置
 * - folder_list_scroll_positions: フォルダ一覧の並び順ごとのスクロール位置
 * - recent_folders:           最近閲覧したフォルダ
 * - tags:                     フォルダに付与するタグ
 * - folder_tags:              フォルダとタグの多対多関係
 */
@Database(
    entities = [
        ImageItem::class,
        SyncStateEntity::class,
        VolumeSyncStateEntity::class,
        FavoriteImageEntity::class,
        FavoriteFolderEntity::class,
        ExcludedFolderEntity::class,
        FolderScrollPositionEntity::class,
        FolderListScrollPositionEntity::class,
        RecentFolderEntity::class,
        TagEntity::class,
        FolderTagEntity::class,
        UnreadableImageEntity::class
    ],
    version = 9,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun imageItemDao(): ImageItemDao
    abstract fun syncStateDao(): SyncStateDao
    abstract fun volumeSyncStateDao(): VolumeSyncStateDao
    abstract fun favoriteImageDao(): FavoriteImageDao
    abstract fun favoriteFolderDao(): FavoriteFolderDao
    abstract fun excludedFolderDao(): ExcludedFolderDao
    abstract fun folderScrollPositionDao(): FolderScrollPositionDao
    abstract fun folderListScrollPositionDao(): FolderListScrollPositionDao
    abstract fun recentFolderDao(): RecentFolderDao
    abstract fun tagDao(): TagDao

    companion object {
        const val DATABASE_NAME = "fastest-picture-viewer.db"

        /** 復旧時に退避する DB の接尾辞 (Issue #88) */
        private const val QUARANTINE_SUFFIX = ".corrupted"

        /** 直近の復旧結果。UI で案内するために保持する (Issue #88) */
        @Volatile
        var lastRecovery: DatabaseRecoveryResult? = null
            private set

        /**
         * 通常の DB を構築する (Issue #88)。
         *
         * 万一 DB を開けなかった場合、壊れたファイルを退避して作り直す。
         * これをしないと起動時にクラッシュし、二度とアプリを使えなくなる。
         * 写真そのものは削除せず、再スキャンで一覧が戻る。
         */
        fun build(context: Context): AppDatabase =
            try {
                open(context)
            } catch (t: Throwable) {
                val reason = DatabaseRecovery.classify(
                    message = t.message,
                    isSqliteException = isSqlite(t)
                )
                if (!DatabaseRecovery.shouldRecreate(reason)) {
                    // 権限・容量など、作り直しても解決しない問題はそのまま伝える
                    throw t
                }
                // 壊れた DB を退避してから作り直す
                val quarantined = quarantine(context)
                lastRecovery = DatabaseRecoveryResult(
                    reason = reason,
                    quarantinedPath = quarantined?.absolutePath
                )
                Log.w(
                    "FPV-Perf",
                    "database recovery: reason=$reason quarantined=${quarantined?.name}",
                    t
                )
                open(context)
            }

        /** 実際に Room を開く。壊れた DB を検出するため、開いた直後に疎通確認する */
        private fun open(context: Context): AppDatabase {
            val db = Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                DATABASE_NAME
            ).addMigrations(
                MIGRATION_1_2,
                MIGRATION_2_3,
                MIGRATION_3_4,
                MIGRATION_4_5,
                MIGRATION_5_6,
                MIGRATION_6_7,
                MIGRATION_7_8,
                MIGRATION_8_9
            ).build()
            // Room は遅延初期化のため、ここで一度触らないと破損が分からない。
            // 起動時に検出したいので、軽いクエリで疎通を確認する (Issue #88)
            try {
                db.openHelper.writableDatabase
            } catch (t: Throwable) {
                runCatching { db.close() }
                throw t
            }
            return db
        }

        /**
         * テスト用: 退避処理だけを呼ぶ (Issue #88)。
         * 破損の検出は Robolectric で再現できないため、退避の副作用だけを検証する。
         */
        internal fun quarantineForTest(context: Context): File? = quarantine(context)

        /** SQLite 由来の例外か (android.database.sqlite 系) */
        private fun isSqlite(t: Throwable): Boolean =
            generateSequence(t as Throwable?) { it.cause }
                .any { it.javaClass.name.startsWith("android.database.sqlite") }

        /**
         * 壊れた DB と付随ファイル (WAL / SHM) を退避する。
         * 退避しておけば、必要なときに中身を調べられる。失敗しても起動を優先する。
         *
         * rename が使えない環境 (別マウント等) もあるため、失敗時はコピーで代替する。
         */
        private fun quarantine(context: Context): File? = try {
            val dir = context.getDatabasePath(DATABASE_NAME).parentFile ?: return null
            val base = File(dir, DATABASE_NAME)
            val target = File(dir, "$DATABASE_NAME$QUARANTINE_SUFFIX")
            // 以前の退避ファイルは上書きする (無限に増やさない)
            if (target.exists()) target.delete()
            if (base.exists()) {
                if (!base.renameTo(target)) {
                    base.copyTo(target, overwrite = true)
                    base.delete()
                }
            }
            // -wal / -shm も一緒に退避する。残すと作り直した DB に適用されて壊れる
            listOf("-wal", "-shm").forEach { suffix ->
                val side = File(dir, "$DATABASE_NAME$suffix")
                if (side.exists()) {
                    val sideTarget = File(dir, "$DATABASE_NAME$QUARANTINE_SUFFIX$suffix")
                    if (!side.renameTo(sideTarget)) {
                        runCatching {
                            side.copyTo(sideTarget, overwrite = true)
                            side.delete()
                        }
                    }
                }
            }
            if (target.exists()) target else null
        } catch (t: Exception) {
            // 退避に失敗しても、DB を消して作り直す方を優先する
            Log.w("FPV-Perf", "quarantine failed", t)
            null
        }

        /** テスト用のメモリ DB を構築する */
        fun buildInMemory(context: Context): AppDatabase =
            Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()

        /**
         * v1 → v2: ボリューム同期状態テーブルを追加する。
         * 既存の画像キャッシュはそのまま維持する。
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `volume_sync_state` (
                        `volumeName` TEXT NOT NULL,
                        `contentGeneration` INTEGER,
                        `lastScanSec` INTEGER NOT NULL,
                        PRIMARY KEY(`volumeName`)
                    )
                    """.trimIndent()
                )
            }
        }

        /** v2 → v3: 除外フォルダテーブルを追加する。既存キャッシュは維持する。 */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `excluded_folders` (
                        `folderKey` TEXT NOT NULL,
                        `volumeName` TEXT NOT NULL,
                        `relativePath` TEXT NOT NULL,
                        `excludedAtSec` INTEGER NOT NULL,
                        PRIMARY KEY(`folderKey`)
                    )
                    """.trimIndent()
                )
            }
        }

        /** v3 → v4: フォルダお気に入りテーブルを追加する。既存キャッシュは維持する。 */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `favorite_folders` (
                        `folderKey` TEXT NOT NULL,
                        `volumeName` TEXT NOT NULL,
                        `relativePath` TEXT NOT NULL,
                        `addedAtSec` INTEGER NOT NULL,
                        PRIMARY KEY(`folderKey`)
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * v4 → v5: 自然順ソートキー列とインデックスを追加する。
         * 既存行のキーは空のまま移行し、フォルダを開いたときに遅延バックフィルする
         * (35万件の一括更新で起動を長時間ブロックしないため)。
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `images` ADD COLUMN `naturalSortKey` TEXT NOT NULL DEFAULT ''"
                )
                db.execSQL("DROP INDEX IF EXISTS `index_images_folderKey_sortName_mediaId`")
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS `index_images_folderKey_naturalSortKey_sortName_mediaId`
                    ON `images` (`folderKey`, `naturalSortKey`, `sortName`, `mediaId`)
                    """.trimIndent()
                )
            }
        }

        /** v5 → v6: フォルダ別スクロール位置と最近閲覧フォルダを追加する。 */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `folder_scroll_positions` (
                        `folderKey` TEXT NOT NULL,
                        `anchorItemId` TEXT,
                        `anchorIndex` INTEGER NOT NULL,
                        `anchorOffsetPx` INTEGER NOT NULL,
                        `updatedAtSec` INTEGER NOT NULL,
                        PRIMARY KEY(`folderKey`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `recent_folders` (
                        `folderKey` TEXT NOT NULL,
                        `openedAtSec` INTEGER NOT NULL,
                        PRIMARY KEY(`folderKey`)
                    )
                    """.trimIndent()
                )
            }
        }

        /** v6 → v7: フォルダ一覧の並び順ごとのスクロール位置を追加する。 */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `folder_list_scroll_positions` (
                        `sortMode` TEXT NOT NULL,
                        `anchorFolderKey` TEXT,
                        `anchorIndex` INTEGER NOT NULL,
                        `anchorOffsetPx` INTEGER NOT NULL,
                        `updatedAtSec` INTEGER NOT NULL,
                        PRIMARY KEY(`sortMode`)
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * v7 → v8: フォルダタグと紐付けテーブルを追加する (Issue #46)。
         * 既存のキャッシュ・お気に入り・除外設定は維持する。
         */
        internal val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `tags` (
                        `tagId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `displayName` TEXT NOT NULL,
                        `normalizedName` TEXT NOT NULL,
                        `createdAtSec` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_tags_normalizedName` ON `tags` (`normalizedName`)"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `folder_tags` (
                        `folderKey` TEXT NOT NULL,
                        `tagId` INTEGER NOT NULL,
                        `addedAtSec` INTEGER NOT NULL,
                        PRIMARY KEY(`folderKey`, `tagId`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_folder_tags_tagId` ON `folder_tags` (`tagId`)"
                )
            }
        }

        /**
         * v8 → v9: 実体を開けなかった画像の記録テーブルを追加する (Issue #77)。
         *
         * OPPO/ColorOS ではフォルダを削除しても MediaStore の行が残ることがあり、
         * その行は走査で毎回返るため削除判定では消せない。開けなかった事実を別テーブルに
         * 記録し、表示クエリから除外する。既存キャッシュ・お気に入り・タグは維持する。
         */
        internal val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `unreadable_images` (
                        `itemId` TEXT NOT NULL,
                        `folderKey` TEXT NOT NULL,
                        `volumeName` TEXT NOT NULL,
                        `detectedAtSec` INTEGER NOT NULL,
                        PRIMARY KEY(`itemId`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_unreadable_images_volumeName` " +
                        "ON `unreadable_images` (`volumeName`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_unreadable_images_folderKey` " +
                        "ON `unreadable_images` (`folderKey`)"
                )
                db.execSQL(
                    "ALTER TABLE `sync_state` ADD COLUMN `lastValidationSec` INTEGER NOT NULL DEFAULT 0"
                )
            }
        }
    }
}

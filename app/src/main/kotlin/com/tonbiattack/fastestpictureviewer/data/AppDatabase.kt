package com.tonbiattack.fastestpictureviewer.data

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory

/**
 * SQLite の破損検出時に DB ファイルを削除させないためのラッパー (Issue #88)。
 *
 * AndroidX の既定の [SupportSQLiteOpenHelper.Callback.onCorruption] は
 * 開いていない DB ファイルを削除する。お気に入り・タグを含むキャッシュを
 * 失わないよう、削除は行わずログだけ残す。
 *
 * 破損した DB をそのまま使い続けるとクエリは失敗するが、
 * データを自動で消すより安全側に倒す。復旧は利用者の操作に委ねる。
 *
 * なお [SupportSQLiteOpenHelper.Configuration.allowDataLossOnRecovery] は
 * 引き継ぐ。明示的にデータ消失を許容した設定を打ち消さないため。
 */
internal class PreservingOpenHelperFactory(
    private val delegate: SupportSQLiteOpenHelper.Factory = FrameworkSQLiteOpenHelperFactory()
) : SupportSQLiteOpenHelper.Factory {

    override fun create(
        configuration: SupportSQLiteOpenHelper.Configuration
    ): SupportSQLiteOpenHelper {
        val original = configuration.callback
        val callback = object : SupportSQLiteOpenHelper.Callback(original.version) {
            override fun onConfigure(db: SupportSQLiteDatabase) = original.onConfigure(db)
            override fun onCreate(db: SupportSQLiteDatabase) = original.onCreate(db)
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                original.onUpgrade(db, oldVersion, newVersion)
            override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                original.onDowngrade(db, oldVersion, newVersion)
            override fun onOpen(db: SupportSQLiteDatabase) = original.onOpen(db)
            override fun onCorruption(db: SupportSQLiteDatabase) {
                // ファイルパスは個人情報を含みうるため出さない
                Log.e("FPV-Perf", "database corruption reported; automatic deletion skipped")
            }
        }
        val safeConfiguration = SupportSQLiteOpenHelper.Configuration
            .builder(configuration.context)
            .name(configuration.name)
            .callback(callback)
            .noBackupDirectory(configuration.useNoBackupDirectory)
            .allowDataLossOnRecovery(configuration.allowDataLossOnRecovery)
            .build()
        return delegate.create(safeConfiguration)
    }
}

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

        /**
         * DB を構築し、その場で開いて疎通を確認する (Issue #88)。
         *
         * Room は遅延初期化のため、開かないまま返すと破損に気づけない。
         * 実機では、破損した DB を開いた例外が Room 内部のコルーチンで発生し、
         * 捕捉できずに起動直後クラッシュした。ここで確実に開き、
         * 失敗を呼び出し元（起動経路）へ伝える。
         */
        fun build(context: Context): AppDatabase {
            val db = Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                DATABASE_NAME
            ).openHelperFactory(PreservingOpenHelperFactory()).addMigrations(
                MIGRATION_1_2,
                MIGRATION_2_3,
                MIGRATION_3_4,
                MIGRATION_4_5,
                MIGRATION_5_6,
                MIGRATION_6_7,
                MIGRATION_7_8,
                MIGRATION_8_9
            ).build()
            try {
                // 実際にファイルを開かせる。破損ならここで例外になる
                db.openHelper.writableDatabase
            } catch (t: Throwable) {
                // 開けなかった DB は閉じる。開いたままにすると、
                // 退避・再作成のときにファイルを掴んで失敗する
                runCatching { db.close() }
                throw t
            }
            return db
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

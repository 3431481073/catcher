package com.example.clip.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.clip.data.db.dao.BlobDao
import com.example.clip.data.db.dao.QueueDao
import com.example.clip.data.db.dao.RecordDao
import com.example.clip.data.db.dao.UsageEventDao
import com.example.clip.data.db.entity.BlobEntity
import com.example.clip.data.db.entity.QueueEntity
import com.example.clip.data.db.entity.RecordEntity
import com.example.clip.data.db.entity.UsageEventEntity

@Database(
    entities = [RecordEntity::class, BlobEntity::class, QueueEntity::class, UsageEventEntity::class],
    version = 3,
    // 🔴 打开导出：`android/app/schemas/` 里那份 `<版本>.json` 是 **Room 自己推导出来的期望 schema**，
    // 手写迁移的 DDL 拿它一比就知道对不对（列类型 / 默认值写法 / 索引名）。
    // 没有它，验证迁移只能靠"装到真机上看看崩不崩" —— 2026-10-11 加 usage_events 那次就是靠它
    // 才发现 `queue.kind` 的 DEFAULT 必须带引号。**改了表结构要连新导出的 json 一起提交。**
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun recordDao(): RecordDao
    abstract fun blobDao(): BlobDao
    abstract fun queueDao(): QueueDao
    abstract fun usageEventDao(): UsageEventDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /** M8：records 加 tags 列。 */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE records ADD COLUMN tags TEXT")
            }
        }

        /**
         * App 使用时长：新表 `usage_events` + `queue` 加 `kind` 列。
         *
         * 🔴 **这是第一次"真的会动到老数据"的迁移**（1→2 只是加一个空列）。
         *    三处都必须和实体推导出来的 schema **逐字对上**，否则 Room 在
         *    **打开库那一刻**抛 `Migration didn't properly handle`：
         *
         *    1. `queue.kind` 的 `DEFAULT 'record'` ↔ `QueueEntity.kind` 的
         *       `@ColumnInfo(defaultValue = "record")` —— 引号也要对上。
         *    2. 索引**名字**。Room 比索引是按名字比的，`index_usage_events_dedup` /
         *       `index_usage_events_sync_status` 这两个名字同时写在实体注解的
         *       `name = ...` 里，不是它推导出来的默认名。
         *    3. `id` 的写法要正好是 Room 给 `@PrimaryKey(autoGenerate = true)` 生成的
         *       那一份：`INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL`。
         *
         *    `records` / `tasks` 的**列一个都没动**（`video-workbench` / `video-panel`
         *    在逐字引用它们，见 `docs/deps.md` §一）。
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `usage_events` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`package_name` TEXT, " +
                        "`event_type` INTEGER NOT NULL, " +
                        "`timestamp_ms` INTEGER NOT NULL, " +
                        "`device_id` TEXT NOT NULL, " +
                        "`collected_at_ms` INTEGER NOT NULL, " +
                        "`sync_status` TEXT NOT NULL)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_usage_events_dedup` " +
                        "ON `usage_events` (`event_type`, `timestamp_ms`, `package_name`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_usage_events_sync_status` " +
                        "ON `usage_events` (`sync_status`)"
                )
                db.execSQL("ALTER TABLE `queue` ADD COLUMN `kind` TEXT NOT NULL DEFAULT 'record'")
            }
        }

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "catcher.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { INSTANCE = it }
            }
    }
}

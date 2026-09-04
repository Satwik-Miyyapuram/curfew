package dev.curfew.app.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase

/**
 * Everything Curfew remembers between launches.
 *
 * The database is encrypted (see [DatabaseKey]) because it records what the user was doing and when
 * — the most sensitive thing the app touches, and the reason the app has no network permission at
 * all. Sessions are stored as the core's own JSON rather than being remodelled into columns: the
 * core owns the shape of a session, and a second model of it here would be a second place for the
 * shape to be wrong.
 */
@Entity(tableName = "policy_state")
data class StateRow(
    @PrimaryKey val key: String,
    val value: String,
)

/**
 * A slice of time spent on a target. Budgets are computed from these rather than from a running
 * total so that a rollover, a clock change or a crash cannot leave a budget permanently spent.
 */
@Entity(tableName = "usage", primaryKeys = ["target", "at"])
data class UsageRow(
    val target: String,
    val at: Long,
    val seconds: Int,
)

@Entity(tableName = "launches", primaryKeys = ["target", "at"])
data class LaunchRow(
    val target: String,
    val at: Long,
)

/**
 * The audit trail the user can read.
 *
 * Curfew's whole claim is that it does something to you that you asked for; being able to see
 * exactly what it did, and when, is what makes that checkable. It never leaves the device.
 */
@Entity(tableName = "audit")
data class AuditRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Long,
    val kind: String,
    val detail: String,
)

@Dao
interface StateDao {
    @Query("SELECT value FROM policy_state WHERE key = :key")
    suspend fun get(key: String): String?

    @androidx.room.Upsert
    suspend fun put(row: StateRow)
}

@Dao
interface UsageDao {
    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun addUsage(row: UsageRow)

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun addLaunch(row: LaunchRow)

    @Query("SELECT * FROM usage WHERE at >= :since")
    suspend fun usageSince(since: Long): List<UsageRow>

    @Query("SELECT * FROM launches WHERE at >= :since")
    suspend fun launchesSince(since: Long): List<LaunchRow>

    /** Old slices are useless to every rule and are the only part of the data that is sensitive. */
    @Query("DELETE FROM usage WHERE at < :before")
    suspend fun pruneUsage(before: Long)

    @Query("DELETE FROM launches WHERE at < :before")
    suspend fun pruneLaunches(before: Long)
}

@Dao
interface AuditDao {
    @Insert
    suspend fun add(row: AuditRow)

    @Query("SELECT * FROM audit ORDER BY at DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<AuditRow>

    @Query("DELETE FROM audit WHERE at < :before")
    suspend fun prune(before: Long)
}

@Database(
    entities = [StateRow::class, UsageRow::class, LaunchRow::class, AuditRow::class],
    version = 1,
    exportSchema = false,
)
abstract class CurfewDatabase : RoomDatabase() {
    abstract fun state(): StateDao
    abstract fun usage(): UsageDao
    abstract fun audit(): AuditDao
}

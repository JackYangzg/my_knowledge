package com.my.knowledge.data.db

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * CQ-4 review cleanup: 4 Migrations in [AppDatabase] (v4→5, v5→6,
 * v6→7, v7→8) each defined a private `addColumnIfMissing` with
 * byte-identical bodies. Collapsed to a single top-level extension
 * function so future migrations don't have to re-paste the
 * `PRAGMA table_info` + `ALTER TABLE` dance.
 *
 * Kept `internal` to module scope — the helper is a migration-time
 * detail and doesn't belong in the public db API.
 */
internal fun SupportSQLiteDatabase.addColumnIfMissing(
    table: String,
    column: String,
    spec: String,
) {
    query("PRAGMA table_info(`$table`)").use { cursor ->
        val nameIndex = cursor.getColumnIndex("name")
        while (cursor.moveToNext()) {
            if (cursor.getString(nameIndex) == column) return
        }
    }
    execSQL("ALTER TABLE `$table` ADD COLUMN `$column` $spec")
}

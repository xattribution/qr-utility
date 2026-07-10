package com.qrutility.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * On-device SQLite store. Holds two tables:
 *   records — source data to generate QR codes from (label, content)
 *   scans   — a durable record of every scanned/generated value
 *
 * Fully offline; nothing here touches the network.
 */
class LocalDb(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "qrutility.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE records(" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "label TEXT NOT NULL DEFAULT '', " +
                "content TEXT NOT NULL, " +
                "created INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE scans(" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "value TEXT NOT NULL, " +
                "type TEXT NOT NULL DEFAULT 'scan', " +
                "ts INTEGER NOT NULL)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // single version for now — nothing to migrate
    }

    /* ---------- records ---------- */
    fun insertRecord(label: String, content: String) {
        writableDatabase.insert("records", null, ContentValues().apply {
            put("label", label)
            put("content", content)
            put("created", System.currentTimeMillis())
        })
    }

    fun replaceRecords(rows: List<Row>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("records", null, null)
            val now = System.currentTimeMillis()
            for (r in rows) {
                db.insert("records", null, ContentValues().apply {
                    put("label", r.label)
                    put("content", r.content)
                    put("created", now)
                })
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun records(limit: Int = 5000): List<Row> {
        val out = ArrayList<Row>()
        readableDatabase.rawQuery(
            "SELECT label, content FROM records ORDER BY id LIMIT ?",
            arrayOf(limit.toString())
        ).use { c ->
            while (c.moveToNext()) out.add(Row(c.getString(0), c.getString(1)))
        }
        return out
    }

    fun recordCount(): Int = count("records")

    fun clearRecords() { writableDatabase.delete("records", null, null) }

    /* ---------- scans ---------- */
    fun insertScan(value: String, type: String) {
        writableDatabase.insert("scans", null, ContentValues().apply {
            put("value", value)
            put("type", type)
            put("ts", System.currentTimeMillis())
        })
    }

    fun scanCount(): Int = count("scans")

    /** Every scan as (value, type, ts) for CSV export, newest first. */
    fun scans(): List<Triple<String, String, Long>> {
        val out = ArrayList<Triple<String, String, Long>>()
        readableDatabase.rawQuery(
            "SELECT value, type, ts FROM scans ORDER BY id DESC", null
        ).use { c ->
            while (c.moveToNext()) out.add(Triple(c.getString(0), c.getString(1), c.getLong(2)))
        }
        return out
    }

    fun clearScans() { writableDatabase.delete("scans", null, null) }

    private fun count(table: String): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM $table", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }
}

/** [DataSource] backed by the on-device [LocalDb]. */
class LocalDataSource(private val db: LocalDb) : DataSource {
    override val label = "On-device (SQLite)"
    override fun test() { db.recordCount() } // touches the db; throws if unusable
    override fun fetchRows(limit: Int): List<Row> = db.records(limit)
    override fun insertScan(value: String, type: String) = db.insertScan(value, type)
}

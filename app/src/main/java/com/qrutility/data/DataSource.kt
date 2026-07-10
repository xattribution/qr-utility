package com.qrutility.data

/** One row of source data used to generate a QR code. */
data class Row(val label: String, val content: String)

/**
 * A place data can be read from (to generate QR codes) and written to
 * (to record scans). Implemented by the on-device SQLite store and by the
 * JDBC-backed network sources.
 *
 * All calls are blocking and MUST be invoked off the main thread
 * (see [DbExecutor]).
 */
interface DataSource {
    /** Human-readable name for the UI. */
    val label: String

    /** Throws on failure; returns normally if the source is reachable/usable. */
    fun test()

    /** Rows to turn into QR codes. */
    fun fetchRows(limit: Int = 5000): List<Row>

    /** Record a scanned (or generated) value. */
    fun insertScan(value: String, type: String)
}

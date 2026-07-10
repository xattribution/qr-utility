package com.qrutility.data

import java.sql.Connection
import java.sql.Driver
import java.util.Properties

/**
 * [DataSource] backed by a JDBC connection to PostgreSQL or MySQL/MariaDB.
 * Drivers are instantiated directly (not via DriverManager) because JDBC
 * ServiceLoader registration is unreliable on Android. All methods block and
 * must run off the main thread.
 */
class JdbcSource(private val profile: ConnectionProfile) : DataSource {

    override val label: String
        get() = profile.name.ifBlank { "${profile.type} @ ${profile.host}" }

    private fun newDriver(): Driver = when (profile.type) {
        DbType.POSTGRES -> org.postgresql.Driver()
        DbType.MYSQL -> org.mariadb.jdbc.Driver()
        DbType.LOCAL -> throw IllegalStateException("Not a network profile")
    }

    private fun url(): String {
        val p = profile.effectivePort()
        return when (profile.type) {
            DbType.POSTGRES -> "jdbc:postgresql://${profile.host}:$p/${profile.database}"
            DbType.MYSQL -> "jdbc:mariadb://${profile.host}:$p/${profile.database}"
            DbType.LOCAL -> throw IllegalStateException("Not a network profile")
        }
    }

    private fun connect(): Connection {
        val props = Properties().apply {
            setProperty("user", profile.user)
            setProperty("password", profile.password)
            when (profile.type) {
                DbType.POSTGRES -> {
                    setProperty("connectTimeout", "8")   // seconds
                    setProperty("socketTimeout", "20")
                    setProperty("loginTimeout", "8")
                }
                DbType.MYSQL -> {
                    setProperty("connectTimeout", "8000") // millis
                    setProperty("socketTimeout", "20000")
                }
                else -> {}
            }
        }
        return newDriver().connect(url(), props)
            ?: throw IllegalStateException("Driver rejected the connection URL")
    }

    override fun test() {
        connect().use { c ->
            c.createStatement().use { st ->
                st.executeQuery("SELECT 1").use { it.next() }
            }
        }
    }

    override fun fetchRows(limit: Int): List<Row> {
        val query = profile.generateQuery.trim()
        require(query.isNotEmpty()) { "No SELECT query configured for this connection" }
        val out = ArrayList<Row>()
        connect().use { c ->
            c.createStatement().use { st ->
                runCatching { st.fetchSize = 200 }
                st.executeQuery(query).use { rs ->
                    val cols = rs.metaData.columnCount
                    while (rs.next() && out.size < limit) {
                        out.add(
                            if (cols >= 2) Row(rs.getString(1) ?: "", rs.getString(2) ?: "")
                            else Row("", rs.getString(1) ?: "")
                        )
                    }
                }
            }
        }
        return out
    }

    override fun insertScan(value: String, type: String) {
        val stmt = profile.insertStatement.trim()
        require(stmt.isNotEmpty()) { "No INSERT statement configured for this connection" }
        val params = stmt.count { it == '?' }
        connect().use { c ->
            c.prepareStatement(stmt).use { ps ->
                if (params >= 1) ps.setString(1, value)
                if (params >= 2) ps.setString(2, type)
                ps.executeUpdate()
            }
        }
    }
}

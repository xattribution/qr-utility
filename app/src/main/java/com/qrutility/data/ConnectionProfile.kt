package com.qrutility.data

import org.json.JSONObject

enum class DbType { LOCAL, POSTGRES, MYSQL }

/** A saved database connection. Credentials are persisted encrypted (see ProfileStore). */
data class ConnectionProfile(
    val id: String,
    var name: String,
    var type: DbType,
    var host: String = "",
    var port: Int = 0,
    var database: String = "",
    var user: String = "",
    var password: String = "",
    var generateQuery: String = "",     // SELECT label, content FROM ...
    var insertStatement: String = ""    // INSERT INTO scans(value, ts) VALUES(?, ?)
) {
    fun effectivePort(): Int = if (port > 0) port else defaultPort(type)

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("type", type.name)
        .put("host", host).put("port", port).put("database", database)
        .put("user", user).put("password", password)
        .put("generateQuery", generateQuery).put("insertStatement", insertStatement)

    companion object {
        fun defaultPort(type: DbType): Int = when (type) {
            DbType.POSTGRES -> 5432
            DbType.MYSQL -> 3306
            DbType.LOCAL -> 0
        }

        fun fromJson(o: JSONObject) = ConnectionProfile(
            id = o.optString("id"),
            name = o.optString("name"),
            type = runCatching { DbType.valueOf(o.optString("type", "POSTGRES")) }
                .getOrDefault(DbType.POSTGRES),
            host = o.optString("host"),
            port = o.optInt("port"),
            database = o.optString("database"),
            user = o.optString("user"),
            password = o.optString("password"),
            generateQuery = o.optString("generateQuery"),
            insertStatement = o.optString("insertStatement")
        )
    }
}

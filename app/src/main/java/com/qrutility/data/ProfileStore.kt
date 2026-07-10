package com.qrutility.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray

/**
 * Persists connection profiles in EncryptedSharedPreferences (Android
 * Keystore-backed), so DB credentials are encrypted at rest. Falls back to
 * plain prefs only if the keystore is unavailable.
 */
class ProfileStore(context: Context) {

    private val prefs: SharedPreferences = create(context.applicationContext)

    private fun create(ctx: Context): SharedPreferences = try {
        val key = MasterKey.Builder(ctx)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            ctx,
            "qr_connections",
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        ctx.getSharedPreferences("qr_connections_plain", Context.MODE_PRIVATE)
    }

    fun all(): List<ConnectionProfile> {
        val raw = prefs.getString("profiles", "[]") ?: "[]"
        val arr = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        return (0 until arr.length()).map { ConnectionProfile.fromJson(arr.getJSONObject(it)) }
    }

    fun upsert(p: ConnectionProfile) {
        val list = all().toMutableList()
        val i = list.indexOfFirst { it.id == p.id }
        if (i >= 0) list[i] = p else list.add(p)
        save(list)
    }

    fun delete(id: String) = save(all().filter { it.id != id })

    private fun save(list: List<ConnectionProfile>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs.edit().putString("profiles", arr.toString()).apply()
    }
}

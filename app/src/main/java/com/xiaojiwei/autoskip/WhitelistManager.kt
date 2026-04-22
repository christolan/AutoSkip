package com.xiaojiwei.autoskip

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

class WhitelistManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("autoskip_whitelist", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_WHITELIST = "whitelist_packages"
        private const val KEY_CUSTOM_KEYWORDS_PREFIX = "keywords_"
        private const val KEY_TOAST_ENABLED = "toast_enabled"

        val DEFAULT_KEYWORDS = listOf(
            "跳过", "关闭", "跳过广告", "关闭广告",
        )
    }

    fun getWhitelistPackages(): Set<String> {
        return prefs.getStringSet(KEY_WHITELIST, emptySet()) ?: emptySet()
    }

    fun addPackage(packageName: String) {
        val current = getWhitelistPackages().toMutableSet()
        current.add(packageName)
        prefs.edit().putStringSet(KEY_WHITELIST, current).apply()
    }

    fun removePackage(packageName: String) {
        val current = getWhitelistPackages().toMutableSet()
        current.remove(packageName)
        prefs.edit().putStringSet(KEY_WHITELIST, current).apply()
        prefs.edit().remove(KEY_CUSTOM_KEYWORDS_PREFIX + packageName).apply()
    }

    fun isInWhitelist(packageName: String): Boolean {
        return getWhitelistPackages().contains(packageName)
    }

    fun getKeywordsForPackage(packageName: String): List<String> {
        val custom = prefs.getString(KEY_CUSTOM_KEYWORDS_PREFIX + packageName, null)
        if (custom != null) {
            return try {
                val arr = JSONArray(custom)
                (0 until arr.length()).map { arr.getString(it) }
            } catch (_: Exception) {
                DEFAULT_KEYWORDS
            }
        }
        return DEFAULT_KEYWORDS
    }

    fun setKeywordsForPackage(packageName: String, keywords: List<String>) {
        val arr = JSONArray()
        keywords.forEach { arr.put(it) }
        prefs.edit().putString(KEY_CUSTOM_KEYWORDS_PREFIX + packageName, arr.toString()).apply()
    }

    fun clearCustomKeywords(packageName: String) {
        prefs.edit().remove(KEY_CUSTOM_KEYWORDS_PREFIX + packageName).apply()
    }

    fun hasCustomKeywords(packageName: String): Boolean {
        return prefs.getString(KEY_CUSTOM_KEYWORDS_PREFIX + packageName, null) != null
    }

    fun isToastEnabled(): Boolean {
        return prefs.getBoolean(KEY_TOAST_ENABLED, false)
    }

    fun setToastEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_TOAST_ENABLED, enabled).apply()
    }
}

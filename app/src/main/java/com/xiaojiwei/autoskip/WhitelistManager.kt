package com.xiaojiwei.autoskip

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

class WhitelistManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("autoskip_whitelist", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_WHITELIST = "whitelist_packages"
        private const val KEY_GLOBAL_KEYWORDS = "global_keywords"
        private const val KEY_TOAST_ENABLED = "toast_enabled"

        val DEFAULT_KEYWORDS = listOf(
            "跳过", "跳过广告", "skip", "Skip", "SKIP", "关闭广告", "点击跳过", "跳过 %ds"
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
    }

    fun isInWhitelist(packageName: String): Boolean {
        return getWhitelistPackages().contains(packageName)
    }

    fun getKeywords(): List<String> {
        val stored = prefs.getString(KEY_GLOBAL_KEYWORDS, null) ?: return DEFAULT_KEYWORDS
        return try {
            val arr = JSONArray(stored)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (_: Exception) {
            DEFAULT_KEYWORDS
        }
    }

    fun setKeywords(keywords: List<String>) {
        val arr = JSONArray()
        keywords.forEach { arr.put(it) }
        prefs.edit().putString(KEY_GLOBAL_KEYWORDS, arr.toString()).apply()
    }

    fun resetKeywords() {
        prefs.edit().remove(KEY_GLOBAL_KEYWORDS).apply()
    }

    fun isToastEnabled(): Boolean {
        return prefs.getBoolean(KEY_TOAST_ENABLED, false)
    }

    fun setToastEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_TOAST_ENABLED, enabled).apply()
    }
}

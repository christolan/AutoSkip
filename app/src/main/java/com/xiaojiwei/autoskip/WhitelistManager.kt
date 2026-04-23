package com.xiaojiwei.autoskip

import android.content.Context
import android.content.SharedPreferences

class WhitelistManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("autoskip_whitelist", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_WHITELIST = "whitelist_packages"
        private const val KEY_GLOBAL_KEYWORDS = "global_keywords"
        private const val KEY_AUTO_SKIP_ENABLED = "auto_skip_enabled"
        private const val KEY_TOAST_ENABLED = "toast_enabled"

        val DEFAULT_KEYWORDS = listOf(
            "跳过", "跳过广告", "skip", "Skip", "SKIP", "关闭广告", "点击跳过",
            "跳过 %ds", "%ds 跳过", "Skip %ds", "%ds Skip"
        )
    }

    init {
        prefs.edit().remove(KEY_GLOBAL_KEYWORDS).apply()
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
        return DEFAULT_KEYWORDS
    }

    fun isAutoSkipEnabled(): Boolean {
        return prefs.getBoolean(KEY_AUTO_SKIP_ENABLED, true)
    }

    fun setAutoSkipEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_SKIP_ENABLED, enabled).apply()
    }

    fun isToastEnabled(): Boolean {
        return prefs.getBoolean(KEY_TOAST_ENABLED, false)
    }

    fun setToastEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_TOAST_ENABLED, enabled).apply()
    }
}

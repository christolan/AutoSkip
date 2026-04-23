package com.xiaojiwei.autoskip

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

class SkipAdService : AccessibilityService() {

    private lateinit var whitelistManager: WhitelistManager

    // 每个包名的窗口首次出现时间，用于限制检测窗口期
    private val windowStartTimes = mutableMapOf<String, Long>()

    // 当前窗口期内已点击的元素，避免重复点击同一个目标
    private val clickedElementSignatures = mutableMapOf<String, MutableSet<String>>()

    // 检测窗口期：窗口出现后 N 毫秒内进行检测
    private val detectionWindowMs = 8_000L

    companion object {
        private const val TAG = "SkipAdService"
        var isRunning = false
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        whitelistManager = WhitelistManager(this)
        isRunning = true
        Log.i(TAG, "AutoSkip service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val packageName = event.packageName?.toString() ?: return

        // 仅处理白名单中的 App
        if (!whitelistManager.isInWhitelist(packageName)) return

        val now = System.currentTimeMillis()

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // 记录窗口切换时间
                windowStartTimes[packageName] = now
                clickedElementSignatures.remove(packageName)
                trySkipAd(packageName)
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // 仅在窗口期内检测内容变化
                val startTime = windowStartTimes[packageName] ?: return
                if (now - startTime <= detectionWindowMs) {
                    trySkipAd(packageName)
                } else {
                    clickedElementSignatures.remove(packageName)
                }
            }
        }
    }

    private fun trySkipAd(packageName: String) {
        val root = rootInActiveWindow ?: return
        val keywords = whitelistManager.getKeywordsForPackage(packageName)

        for (keyword in keywords) {
            val nodes = root.findAccessibilityNodeInfosByText(keyword)
            if (nodes.isNullOrEmpty()) continue

            for (node in nodes) {
                if (tryClickNode(node, keyword)) {
                    return
                }
            }
        }
    }

    private fun tryClickNode(node: AccessibilityNodeInfo, keyword: String): Boolean {
        // 检查节点文本是否真正包含关键词（避免部分匹配误触）
        val nodeText = node.text?.toString() ?: ""
        if (!nodeText.contains(keyword, ignoreCase = true) &&
            node.contentDescription?.toString()?.contains(keyword, ignoreCase = true) != true
        ) {
            return false
        }

        val clickedElements = clickedElementSignatures.getOrPut(
            node.packageName?.toString() ?: return false
        ) { mutableSetOf() }

        // 如果节点本身可点击，直接点击
        if (node.isClickable) {
            val signature = buildElementSignature(node)
            if (signature in clickedElements) return false

            val result = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (result) {
                clickedElements.add(signature)
                Log.i(TAG, "Clicked: '$nodeText' in ${node.packageName}")
                showToast("已跳过广告")
                return true
            }
        }

        // 否则向上查找可点击的父节点
        var parent = node.parent
        var depth = 0
        while (parent != null && depth < 5) {
            if (parent.isClickable) {
                val signature = buildElementSignature(parent)
                if (signature in clickedElements) {
                    parent = parent.parent
                    depth++
                    continue
                }

                val result = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (result) {
                    clickedElements.add(signature)
                    Log.i(TAG, "Clicked parent of: '$nodeText' in ${node.packageName}")
                    showToast("已跳过广告")
                    return true
                }
            }
            parent = parent.parent
            depth++
        }

        return false
    }

    private fun buildElementSignature(node: AccessibilityNodeInfo): String {
        val bounds = Rect().also(node::getBoundsInScreen)
        return listOf(
            node.packageName?.toString().orEmpty(),
            node.windowId.toString(),
            node.viewIdResourceName.orEmpty(),
            node.className?.toString().orEmpty(),
            bounds.flattenToString()
        ).joinToString("|")
    }

    override fun onInterrupt() {
        Log.w(TAG, "AutoSkip service interrupted")
    }

    private val handler = Handler(Looper.getMainLooper())

    private fun showToast(message: String) {
        if (!whitelistManager.isToastEnabled()) return
        handler.post {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        clickedElementSignatures.clear()
        windowStartTimes.clear()
        Log.i(TAG, "AutoSkip service destroyed")
    }
}

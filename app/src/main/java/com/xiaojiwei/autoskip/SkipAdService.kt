@file:Suppress("DEPRECATION")

package com.xiaojiwei.autoskip

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import java.util.ArrayDeque

class SkipAdService : AccessibilityService() {

    private lateinit var whitelistManager: WhitelistManager

    // 每个包名的窗口首次出现时间，用于限制检测窗口期
    private val windowStartTimes = mutableMapOf<String, Long>()

    // 当前窗口期内已点击的元素，避免重复点击同一个目标
    private val clickedElementSignatures = mutableMapOf<String, MutableSet<String>>()

    // WINDOW_CONTENT_CHANGED 的上次处理时间，用于 debounce
    private val lastContentChangedTimes = mutableMapOf<String, Long>()

    private val finalizeDetectionRunnables = mutableMapOf<String, Runnable>()

    // 检测窗口期：窗口出现后 N 毫秒内进行检测
    private val detectionWindowMs = 5_000L
    private val contentChangedDebounceMs = 150L

    private data class ClickTarget(
        val node: AccessibilityNodeInfo,
        val depth: Int
    )

    private data class SkipCandidate(
        val targetNode: AccessibilityNodeInfo,
        val matchedText: String,
        val keyword: String,
        val score: Int,
        val depth: Int,
        val useGesture: Boolean = false
    )

    companion object {
        private const val TAG = "SkipAdService"
        private const val MATCH_RULE_LABEL = "包含“跳过”"
        private val inlineSeparatorRegex = Regex("\\s*[|｜·•・/\\\\]+\\s*")
        private val whitespaceRegex = Regex("\\s+")
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
        val isAutoSkipEnabled = whitelistManager.isAutoSkipEnabled()

        val now = System.currentTimeMillis()

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                stopDetectionForBackgroundedApps(currentPackageName = packageName)

                // 仅处理白名单中的 App
                if (!whitelistManager.isInWhitelist(packageName)) return

                val existingStart = windowStartTimes[packageName]
                val isNewSession = existingStart == null || (now - existingStart > detectionWindowMs)
                if (isNewSession) {
                    windowStartTimes[packageName] = now
                    lastContentChangedTimes.remove(packageName)
                    clickedElementSignatures.remove(packageName)
                    scheduleDetectionSummary(packageName)
                }
                if (isAutoSkipEnabled) {
                    trySkipAd(packageName, "WINDOW_STATE_CHANGED")
                }
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // 仅处理白名单中的 App
                if (!whitelistManager.isInWhitelist(packageName)) return

                // 仅在窗口期内检测内容变化
                val startTime = windowStartTimes[packageName] ?: return
                if (now - startTime <= detectionWindowMs) {
                    if (isAutoSkipEnabled) {
                        // debounce：同一包名 150ms 内只处理一次，避免动画期间刷屏 BFS
                        val lastTime = lastContentChangedTimes[packageName] ?: 0L
                        if (now - lastTime >= contentChangedDebounceMs) {
                            lastContentChangedTimes[packageName] = now
                            trySkipAd(packageName, "WINDOW_CONTENT_CHANGED")
                        }
                    }
                } else {
                    finalizeAndClearDetectionState(packageName)
                }
            }
        }
    }

    private fun stopDetectionForBackgroundedApps(currentPackageName: String) {
        val packagesToStop = windowStartTimes.keys.filter { it != currentPackageName }
        packagesToStop.forEach { packageName ->
            finalizeAndClearDetectionState(packageName)
        }
    }

    private fun finalizeAndClearDetectionState(packageName: String) {
        finalizeDetectionRunnables.remove(packageName)?.let(handler::removeCallbacks)
        windowStartTimes.remove(packageName)
        lastContentChangedTimes.remove(packageName)
        clickedElementSignatures.remove(packageName)
    }

    private fun trySkipAd(packageName: String, eventTypeName: String) {
        val root = rootInActiveWindow ?: return
        val nodesToRecycle = mutableListOf<AccessibilityNodeInfo>()
        var candidates: List<SkipCandidate>? = null

        try {
            val rootBounds = Rect().also(root::getBoundsInScreen)
            val clickedElements = clickedElementSignatures.getOrPut(packageName) { mutableSetOf() }
            candidates = collectCandidates(root, rootBounds, clickedElements, nodesToRecycle)

            if (candidates.isEmpty()) return

            val sorted = candidates.sortedWith(compareByDescending<SkipCandidate> { it.score }.thenBy { it.depth })

            for (candidate in sorted) {
                val signature = buildElementSignature(candidate.targetNode)
                if (signature in clickedElements) continue
                val result = if (candidate.useGesture) {
                    performGestureTap(candidate.targetNode)
                } else {
                    candidate.targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
                if (!result) continue
                clickedElements.add(signature)
                val clickType = when {
                    candidate.useGesture -> "gesture"
                    candidate.depth == 0 -> "self"
                    else -> "parent(depth=${candidate.depth})"
                }
                Log.i(
                    TAG,
                    "Clicked $clickType: '${candidate.matchedText}' via '${candidate.keyword}' score=${candidate.score} in ${candidate.targetNode.packageName}"
                )
                showToast("已跳过广告")
                // 点击成功后让 debounce 冷却到窗口结束，不再继续 BFS
                lastContentChangedTimes[packageName] = System.currentTimeMillis() + detectionWindowMs
                break
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in trySkipAd", e)
        } finally {
            root.recycle()
            candidates?.forEach { runCatching { it.targetNode.recycle() } }
            nodesToRecycle.forEach { runCatching { it.recycle() } }
        }
    }

    private fun scheduleDetectionSummary(packageName: String) {
        finalizeDetectionRunnables.remove(packageName)?.let(handler::removeCallbacks)
        val runnable = Runnable {
            finalizeAndClearDetectionState(packageName)
        }
        finalizeDetectionRunnables[packageName] = runnable
        handler.postDelayed(runnable, detectionWindowMs)
    }

    private fun collectCandidates(
        root: AccessibilityNodeInfo,
        rootBounds: Rect,
        clickedElements: Set<String>,
        nodesToRecycle: MutableList<AccessibilityNodeInfo>
    ): List<SkipCandidate> {
        val candidates = mutableListOf<SkipCandidate>()
        val candidateKeys = mutableSetOf<String>()

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        for (index in 0 until root.childCount) {
            root.getChild(index)?.let(queue::addLast)
        }
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val retained = addCandidate(
                node = node,
                rootBounds = rootBounds,
                clickedElements = clickedElements,
                candidateKeys = candidateKeys,
                candidates = candidates
            )

            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(queue::addLast)
            }
            if (!retained) {
                nodesToRecycle.add(node)
            }
        }

        return candidates
    }

    private fun addCandidate(
        node: AccessibilityNodeInfo,
        rootBounds: Rect,
        clickedElements: Set<String>,
        candidateKeys: MutableSet<String>,
        candidates: MutableList<SkipCandidate>
    ): Boolean {
        val matchedText = findMatchedText(node) ?: return false
        val clickTarget = findClickTarget(node, rootBounds)

        if (clickTarget == null) {
            // 精确匹配"跳过"但无可点击祖先：降级为坐标手势模拟点击
            if (matchedText != "跳过") return false
            if (!node.isVisibleToUser || !isReasonableTarget(node, rootBounds)) return false
            val signature = buildElementSignature(node)
            if (signature in clickedElements) return false
            val candidateKey = "$signature|gesture"
            if (!candidateKeys.add(candidateKey)) return false
            val score = scoreCandidate(node, ClickTarget(node, 0), matchedText, rootBounds)
            if (score <= 0) return false
            candidates.add(
                SkipCandidate(
                    targetNode = node,
                    matchedText = matchedText,
                    keyword = MATCH_RULE_LABEL,
                    score = score,
                    depth = 0,
                    useGesture = true
                )
            )
            return true
        }

        val targetNode = clickTarget.node

        val targetSignature = buildElementSignature(targetNode)
        if (targetSignature in clickedElements) {
            if (targetNode !== node) targetNode.recycle()
            return false
        }

        val candidateKey = buildElementSignature(node) + "|" + targetSignature
        if (!candidateKeys.add(candidateKey)) {
            if (targetNode !== node) targetNode.recycle()
            return false
        }

        val score = scoreCandidate(node, clickTarget, matchedText, rootBounds)
        if (score <= 0) {
            if (targetNode !== node) targetNode.recycle()
            return false
        }

        candidates.add(
            SkipCandidate(
                targetNode = targetNode,
                matchedText = matchedText,
                keyword = MATCH_RULE_LABEL,
                score = score,
                depth = clickTarget.depth
            )
        )
        return targetNode === node
    }

    private fun findClickTarget(node: AccessibilityNodeInfo, rootBounds: Rect): ClickTarget? {
        if (!node.isVisibleToUser) return null

        if (node.isClickable && isReasonableTarget(node, rootBounds)) {
            return ClickTarget(node, 0)
        }

        var parent = node.parent
        var depth = 0
        while (parent != null && depth < 5) {
            depth++
            if (parent.isClickable && isReasonableTarget(parent, rootBounds)) {
                return ClickTarget(parent, depth)
            }
            val nextParent = parent.parent
            parent.recycle()
            parent = nextParent
        }

        parent?.recycle()
        return null
    }

    private fun isReasonableTarget(node: AccessibilityNodeInfo, rootBounds: Rect): Boolean {
        if (!node.isVisibleToUser) return false

        val bounds = Rect().also(node::getBoundsInScreen)
        val rootArea = rootBounds.width().coerceAtLeast(1) * rootBounds.height().coerceAtLeast(1)
        val nodeArea = bounds.width().coerceAtLeast(0) * bounds.height().coerceAtLeast(0)
        if (nodeArea == 0) return false

        val areaRatio = nodeArea.toDouble() / rootArea.toDouble()
        val widthRatio = bounds.width().toDouble() / rootBounds.width().coerceAtLeast(1).toDouble()
        val heightRatio = bounds.height().toDouble() / rootBounds.height().coerceAtLeast(1).toDouble()

        return areaRatio <= 0.45 &&
            !(widthRatio >= 0.95 && heightRatio >= 0.5) &&
            heightRatio <= 0.45
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

    private fun findMatchedText(node: AccessibilityNodeInfo): String? {
        return sequenceOf(node.text?.toString(), node.contentDescription?.toString())
            .filterNotNull()
            .map(::normalizeCandidateText)
            .filter(String::isNotEmpty)
            .firstOrNull { "\u8df3\u8fc7" in it && it.length <= 6 }
    }

    private fun scoreCandidate(
        matchedNode: AccessibilityNodeInfo,
        clickTarget: ClickTarget,
        matchedText: String,
        rootBounds: Rect
    ): Int {
        val targetBounds = Rect().also(clickTarget.node::getBoundsInScreen)
        val rootWidth = rootBounds.width().coerceAtLeast(1)
        val rootHeight = rootBounds.height().coerceAtLeast(1)
        val rootArea = rootWidth * rootHeight
        val targetArea = targetBounds.width().coerceAtLeast(1) * targetBounds.height().coerceAtLeast(1)
        val areaRatio = targetArea.toDouble() / rootArea.toDouble()
        val centerXRatio = (targetBounds.centerX() - rootBounds.left).toDouble() / rootWidth.toDouble()
        val centerYRatio = (targetBounds.centerY() - rootBounds.top).toDouble() / rootHeight.toDouble()
        val widthRatio = targetBounds.width().toDouble() / rootWidth.toDouble()
        val heightRatio = targetBounds.height().toDouble() / rootHeight.toDouble()

        var score = 120

        score += when {
            matchedText == "跳过" -> 24
            matchedText.length <= 8 -> 18
            else -> 10
        }

        score += when (clickTarget.depth) {
            0 -> 20
            1 -> 14
            2 -> 10
            3 -> 6
            else -> 2
        }

        score += when {
            centerXRatio >= 0.72 -> 18
            centerXRatio >= 0.55 -> 8
            centerXRatio <= 0.15 -> 8
            else -> -2
        }

        score += when {
            centerYRatio <= 0.22 -> 22
            centerYRatio <= 0.38 -> 12
            centerYRatio <= 0.55 -> 4
            else -> -10
        }

        score += when {
            areaRatio <= 0.015 -> 18
            areaRatio <= 0.04 -> 12
            areaRatio <= 0.08 -> 6
            areaRatio <= 0.16 -> 0
            else -> -20
        }

        if (widthRatio > 0.5) score -= 16
        if (heightRatio > 0.18) score -= 10

        val className = clickTarget.node.className?.toString()?.lowercase().orEmpty()
        score += when {
            "button" in className -> 12
            "imageview" in className -> 8
            "textview" in className -> 6
            else -> 0
        }

        if (!matchedNode.isClickable && clickTarget.node.isClickable) {
            score += 4
        }

        return score
    }

    private fun normalizeCandidateText(text: String): String {
        return text
            .trim()
            .replace(inlineSeparatorRegex, " ")
            .replace(whitespaceRegex, " ")
    }

    override fun onInterrupt() {
        Log.w(TAG, "AutoSkip service interrupted")
    }

    private val handler = Handler(Looper.getMainLooper())

    private fun performGestureTap(node: AccessibilityNodeInfo): Boolean {
        val bounds = Rect().also(node::getBoundsInScreen)
        val x = bounds.centerX().toFloat()
        val y = bounds.centerY().toFloat()
        val path = Path().apply { moveTo(x, y); lineTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 50L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    private fun showToast(message: String) {
        if (!whitelistManager.isToastEnabled()) return
        handler.post {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        finalizeDetectionRunnables.values.forEach(handler::removeCallbacks)
        finalizeDetectionRunnables.clear()
        clickedElementSignatures.clear()
        lastContentChangedTimes.clear()
        windowStartTimes.clear()
        Log.i(TAG, "AutoSkip service destroyed")
    }
}

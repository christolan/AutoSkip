package com.xiaojiwei.autoskip

import android.accessibilityservice.AccessibilityService
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

    // 检测窗口期：窗口出现后 N 毫秒内进行检测
    private val detectionWindowMs = 8_000L

    private data class KeywordPattern(
        val keyword: String,
        val regex: Regex,
        val normalizedLength: Int,
        val searchTerms: List<String>
    )

    private data class ClickTarget(
        val node: AccessibilityNodeInfo,
        val depth: Int
    )

    private data class SkipCandidate(
        val matchedNode: AccessibilityNodeInfo,
        val targetNode: AccessibilityNodeInfo,
        val matchedText: String,
        val keyword: String,
        val score: Int,
        val depth: Int
    )

    companion object {
        private const val TAG = "SkipAdService"
        private val sentencePunctuation = setOf('，', '。', '！', '？', '；', '：', ',', '.', '!', '?', ';', ':', '\n')
        private val resourceHintKeywords = listOf("skip", "close", "dismiss", "countdown", "timer")
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
        val isAutoSkipEnabled = whitelistManager.isAutoSkipEnabled()

        val now = System.currentTimeMillis()

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // 记录窗口切换时间
                windowStartTimes[packageName] = now
                clickedElementSignatures.remove(packageName)
                if (isAutoSkipEnabled) {
                    trySkipAd(packageName)
                }
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // 仅在窗口期内检测内容变化
                val startTime = windowStartTimes[packageName] ?: return
                if (now - startTime <= detectionWindowMs) {
                    if (isAutoSkipEnabled) {
                        trySkipAd(packageName)
                    }
                } else {
                    clickedElementSignatures.remove(packageName)
                }
            }
        }
    }

    private fun trySkipAd(packageName: String) {
        val root = rootInActiveWindow ?: return
        val rootBounds = Rect().also(root::getBoundsInScreen)
        val keywordPatterns = whitelistManager.getKeywords()
            .mapNotNull(::buildKeywordPattern)
        if (keywordPatterns.isEmpty()) return
        val clickedElements = clickedElementSignatures.getOrPut(packageName) { mutableSetOf() }
        val candidates = collectCandidates(root, rootBounds, keywordPatterns, clickedElements)

        for (candidate in candidates.sortedWith(compareByDescending<SkipCandidate> { it.score }.thenBy { it.depth })) {
            if (tryClickCandidate(candidate, clickedElements)) {
                return
            }
        }
    }

    private fun collectCandidates(
        root: AccessibilityNodeInfo,
        rootBounds: Rect,
        keywordPatterns: List<KeywordPattern>,
        clickedElements: Set<String>
    ): List<SkipCandidate> {
        val candidates = mutableListOf<SkipCandidate>()
        val candidateKeys = mutableSetOf<String>()

        for (pattern in keywordPatterns) {
            for (searchTerm in pattern.searchTerms) {
                val nodes = root.findAccessibilityNodeInfosByText(searchTerm)
                if (nodes.isNullOrEmpty()) continue
                for (node in nodes) {
                    addCandidate(
                        node = node,
                        pattern = pattern,
                        rootBounds = rootBounds,
                        clickedElements = clickedElements,
                        candidateKeys = candidateKeys,
                        candidates = candidates
                    )
                }
            }
        }

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            for (pattern in keywordPatterns) {
                addCandidate(
                    node = node,
                    pattern = pattern,
                    rootBounds = rootBounds,
                    clickedElements = clickedElements,
                    candidateKeys = candidateKeys,
                    candidates = candidates
                )
            }

            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(queue::addLast)
            }
        }

        return candidates
    }

    private fun addCandidate(
        node: AccessibilityNodeInfo,
        pattern: KeywordPattern,
        rootBounds: Rect,
        clickedElements: Set<String>,
        candidateKeys: MutableSet<String>,
        candidates: MutableList<SkipCandidate>
    ) {
        val matchedText = findMatchedText(node, pattern) ?: return
        val clickTarget = findClickTarget(node, rootBounds) ?: return
        val targetSignature = buildElementSignature(clickTarget.node)
        if (targetSignature in clickedElements) return

        val candidateKey = buildElementSignature(node) + "|" + targetSignature + "|" + pattern.keyword
        if (!candidateKeys.add(candidateKey)) return

        val score = scoreCandidate(node, clickTarget, matchedText, pattern, rootBounds)
        if (score <= 0) return

        candidates.add(
            SkipCandidate(
                matchedNode = node,
                targetNode = clickTarget.node,
                matchedText = matchedText,
                keyword = pattern.keyword,
                score = score,
                depth = clickTarget.depth
            )
        )
    }

    private fun tryClickCandidate(candidate: SkipCandidate, clickedElements: MutableSet<String>): Boolean {
        val signature = buildElementSignature(candidate.targetNode)
        if (signature in clickedElements) return false

        val result = candidate.targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (result) {
            clickedElements.add(signature)
            val clickType = if (candidate.depth == 0) "self" else "parent(depth=${candidate.depth})"
            Log.i(
                TAG,
                "Clicked $clickType: '${candidate.matchedText}' via '${candidate.keyword}' score=${candidate.score} in ${candidate.targetNode.packageName}"
            )
            showToast("已跳过广告")
            return true
        }

        return false
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
            parent = parent.parent
        }

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

    private fun buildKeywordPattern(keyword: String): KeywordPattern? {
        val normalizedKeyword = keyword.trim()
        if (normalizedKeyword.isEmpty()) return null

        return KeywordPattern(
            keyword = normalizedKeyword,
            regex = buildKeywordRegex(normalizedKeyword),
            normalizedLength = normalizeCandidateText(normalizedKeyword).length,
            searchTerms = buildSearchTerms(normalizedKeyword)
        )
    }

    private fun buildSearchTerms(keyword: String): List<String> {
        val normalizedKeyword = keyword.trim()
        if (normalizedKeyword.isEmpty()) return emptyList()

        val literalTerms = normalizedKeyword
            .replace("%ds", " ", ignoreCase = true)
            .replace("%d", " ", ignoreCase = true)
            .split(Regex("\\s+"))
            .map(String::trim)
            .filter(String::isNotEmpty)
            .sortedByDescending(String::length)

        return if (literalTerms.isEmpty()) {
            listOf(normalizedKeyword)
        } else {
            literalTerms.distinct()
        }
    }

    private fun findMatchedText(node: AccessibilityNodeInfo, pattern: KeywordPattern): String? {
        return sequenceOf(node.text?.toString(), node.contentDescription?.toString())
            .filterNotNull()
            .map(::normalizeCandidateText)
            .filter(String::isNotEmpty)
            .firstOrNull { candidate ->
                candidate.length <= maxOf(pattern.normalizedLength + 6, 8) &&
                    candidate.none(sentencePunctuation::contains) &&
                    pattern.regex.matches(candidate)
            }
    }

    private fun scoreCandidate(
        matchedNode: AccessibilityNodeInfo,
        clickTarget: ClickTarget,
        matchedText: String,
        pattern: KeywordPattern,
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
            matchedText.equals(pattern.keyword, ignoreCase = true) -> 24
            matchedText.length <= pattern.normalizedLength + 2 -> 18
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
            else -> -6
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

        val resourceText = listOf(
            clickTarget.node.viewIdResourceName,
            matchedNode.viewIdResourceName
        ).filterNotNull().joinToString(" ").lowercase()
        score += when {
            resourceHintKeywords.any { hint -> hint in resourceText } -> 10
            else -> 0
        }

        if (!matchedNode.isClickable && clickTarget.node.isClickable) {
            score += 4
        }

        return score
    }

    private fun buildKeywordRegex(keyword: String): Regex {
        val pattern = StringBuilder("^")
        val normalizedKeyword = keyword.trim()
        var index = 0

        while (index < normalizedKeyword.length) {
            when {
                normalizedKeyword.startsWith("%ds", index, ignoreCase = true) -> {
                    pattern.append("\\s*\\d{1,2}\\s*[sS秒]?")
                    index += 3
                }

                normalizedKeyword.startsWith("%d", index, ignoreCase = true) -> {
                    pattern.append("\\s*\\d{1,2}")
                    index += 2
                }

                normalizedKeyword[index].isWhitespace() -> {
                    pattern.append("\\s*")
                    index++
                }

                else -> {
                    pattern.append(Regex.escape(normalizedKeyword[index].toString()))
                    index++
                }
            }
        }

        pattern.append("(?:\\s*按钮)?$")
        return Regex(pattern.toString(), RegexOption.IGNORE_CASE)
    }

    private fun normalizeCandidateText(text: String): String {
        return text.trim().replace(Regex("\\s+"), " ")
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

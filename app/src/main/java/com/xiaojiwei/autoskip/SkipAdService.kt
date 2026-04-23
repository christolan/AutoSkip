@file:Suppress("DEPRECATION")

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

    // 开屏检测窗口内的暂存日志，仅在最终未命中候选时落盘
    private val pendingDetectionSessions = mutableMapOf<String, PendingDetectionSession>()
    private val finalizeDetectionRunnables = mutableMapOf<String, Runnable>()

    // 检测窗口期：窗口出现后 N 毫秒内进行检测
    private val detectionWindowMs = 5_000L

    private data class ClickTarget(
        val node: AccessibilityNodeInfo,
        val depth: Int
    )

    private data class SkipCandidate(
        val targetNode: AccessibilityNodeInfo,
        val matchedText: String,
        val keyword: String,
        val score: Int,
        val depth: Int
    )

    data class ClickableNodeLog(
        val text: String?,
        val contentDescription: String?,
        val bounds: String,
        val className: String?,
        val resourceName: String?,
        val isClickable: Boolean,
        val containsSkipText: Boolean,
        val isEnabled: Boolean,
        val isReasonableTarget: Boolean,
        val note: String
    )

    data class CandidateLog(
        val matchedText: String,
        val keyword: String,
        val score: Int,
        val depth: Int,
        val bounds: String,
        val className: String?,
        val resourceName: String?,
        var isClicked: Boolean = false,
        var filterReason: String? = null
    )

    data class DetectionLog(
        val packageName: String,
        val timestamp: Long,
        val eventType: String,
        val rootBounds: String,
        val keywords: List<String>,
        val candidates: List<CandidateLog>,
        val clickableNodes: List<ClickableNodeLog> = emptyList(),
        val resultSummary: String
    )

    private data class PendingDetectionSession(
        val startTimestamp: Long,
        var latestEventType: String,
        var latestRootBounds: String = "unknown",
        var keywords: List<String> = emptyList(),
        var clickedCandidate: CandidateLog? = null,
        var clickedSummary: String? = null,
        val clickableNodes: LinkedHashMap<String, ClickableNodeLog> = linkedMapOf()
    )

    companion object {
        private const val TAG = "SkipAdService"
        private const val MATCH_RULE_LABEL = "包含“跳过”"
        private val inlineSeparatorRegex = Regex("\\s*[|｜·•・/\\\\]+\\s*")
        var isRunning = false
            private set
        val appLogs = mutableMapOf<String, MutableList<DetectionLog>>()
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
                // 若该包名此前没有窗口期，或上一个窗口期已过期（超过 detectionWindowMs），
                // 则视为新一轮启动/回到前台，清空旧日志开始新 session 记录；
                // 否则只是同一次 session 内的页面跳转，保留已有日志继续追加。
                val isNewSession = existingStart == null || (now - existingStart > detectionWindowMs)
                windowStartTimes[packageName] = now
                clickedElementSignatures.remove(packageName)
                if (isNewSession) {
                    appLogs[packageName] = mutableListOf()
                    pendingDetectionSessions[packageName] = PendingDetectionSession(
                        startTimestamp = now,
                        latestEventType = "WINDOW_STATE_CHANGED"
                    )
                } else {
                    pendingDetectionSessions.getOrPut(packageName) {
                        PendingDetectionSession(
                            startTimestamp = now,
                            latestEventType = "WINDOW_STATE_CHANGED"
                        )
                    }.latestEventType = "WINDOW_STATE_CHANGED"
                }
                scheduleDetectionSummary(packageName)
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
                        trySkipAd(packageName, "WINDOW_CONTENT_CHANGED")
                    }
                } else {
                    finalizeAndClearDetectionState(packageName, "TIMEOUT")
                }
            }
        }
    }

    private fun stopDetectionForBackgroundedApps(currentPackageName: String) {
        val packagesToStop = windowStartTimes.keys.filter { it != currentPackageName }
        packagesToStop.forEach { packageName ->
            finalizeAndClearDetectionState(packageName, "BACKGROUND")
        }
    }

    private fun finalizeAndClearDetectionState(packageName: String, trigger: String) {
        finalizeDetectionRunnables.remove(packageName)?.let(handler::removeCallbacks)
        finalizeDetectionSession(packageName, trigger)
        windowStartTimes.remove(packageName)
        clickedElementSignatures.remove(packageName)
    }

    private fun trySkipAd(packageName: String, eventTypeName: String) {
        var logRootBounds = "unknown"
        var logKeywords = emptyList<String>()

        val root = rootInActiveWindow
        if (root == null) {
            return
        }

        val nodesToRecycle = mutableListOf<AccessibilityNodeInfo>()
        var candidates: List<SkipCandidate>? = null

        try {
            val rootBounds = Rect().also(root::getBoundsInScreen)
            logRootBounds = "${rootBounds.width()}x${rootBounds.height()}"

            logKeywords = listOf(MATCH_RULE_LABEL)
            val pendingSession = pendingDetectionSessions.getOrPut(packageName) {
                PendingDetectionSession(
                    startTimestamp = System.currentTimeMillis(),
                    latestEventType = eventTypeName
                )
            }.also {
                it.latestEventType = eventTypeName
                it.latestRootBounds = logRootBounds
                it.keywords = logKeywords
            }
            collectObservedNodes(root, rootBounds, pendingSession)

            val clickedElements = clickedElementSignatures.getOrPut(packageName) { mutableSetOf() }
            candidates = collectCandidates(root, rootBounds, clickedElements, nodesToRecycle)

            if (candidates.isEmpty()) {
                return
            }

            val candidateLogMap = candidates.associateWith { candidate ->
                val bounds = runCatching {
                    Rect().also { candidate.targetNode.getBoundsInScreen(it) }
                }.getOrDefault(Rect())
                CandidateLog(
                    matchedText = candidate.matchedText,
                    keyword = candidate.keyword,
                    score = candidate.score,
                    depth = candidate.depth,
                    bounds = "[${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}]",
                    className = candidate.targetNode.className?.toString(),
                    resourceName = candidate.targetNode.viewIdResourceName
                )
            }

            val clickedElements2 = clickedElementSignatures.getOrPut(packageName) { mutableSetOf() }
            val sorted = candidates.sortedWith(compareByDescending<SkipCandidate> { it.score }.thenBy { it.depth })

            for (candidate in sorted) {
                val signature = buildElementSignature(candidate.targetNode)
                val cLog = candidateLogMap[candidate]!!
                if (signature in clickedElements2) {
                    cLog.filterReason = "已在当前窗口期点击过"
                    continue
                }
                val result = candidate.targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (!result) {
                    cLog.filterReason = "performAction 返回 false"
                    continue
                }
                clickedElements2.add(signature)
                cLog.isClicked = true
                val clickType = if (candidate.depth == 0) "self" else "parent(depth=${candidate.depth})"
                pendingSession.clickedCandidate = cLog.copy(isClicked = true, filterReason = null)
                pendingSession.clickedSummary =
                    "已点击 '${candidate.matchedText}' (关键词=${candidate.keyword}, 得分=${candidate.score}, 方式=$clickType)"
                Log.i(
                    TAG,
                    "Clicked $clickType: '${candidate.matchedText}' via '${candidate.keyword}' score=${candidate.score} in ${candidate.targetNode.packageName}"
                )
                showToast("已跳过广告")
                break
            }

            candidateLogMap.values.forEach { cLog ->
                if (!cLog.isClicked && cLog.filterReason == null) {
                    cLog.filterReason = "未尝试（已有更优候选被点击）"
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in trySkipAd", e)
        } finally {
            root.recycle()
            candidates?.forEach { runCatching { it.targetNode.recycle() } }
            nodesToRecycle.forEach { runCatching { it.recycle() } }
        }
    }

    private fun saveLog(
        packageName: String,
        timestamp: Long,
        eventType: String,
        rootBounds: String,
        keywords: List<String>,
        candidates: List<CandidateLog>,
        clickableNodes: List<ClickableNodeLog>,
        resultSummary: String
    ) {
        val log = DetectionLog(
            packageName = packageName,
            timestamp = timestamp,
            eventType = eventType,
            rootBounds = rootBounds,
            keywords = keywords,
            candidates = candidates,
            clickableNodes = clickableNodes,
            resultSummary = resultSummary
        )
        appLogs.getOrPut(packageName) { mutableListOf() }.add(log)
    }

    private fun scheduleDetectionSummary(packageName: String) {
        finalizeDetectionRunnables.remove(packageName)?.let(handler::removeCallbacks)
        val runnable = Runnable {
            finalizeAndClearDetectionState(packageName, "TIMEOUT")
        }
        finalizeDetectionRunnables[packageName] = runnable
        handler.postDelayed(runnable, detectionWindowMs)
    }

    private fun finalizeDetectionSession(packageName: String, trigger: String) {
        val session = pendingDetectionSessions.remove(packageName) ?: return
        val clickedCandidate = session.clickedCandidate
        if (clickedCandidate != null) {
            saveLog(
                packageName = packageName,
                timestamp = session.startTimestamp,
                eventType = "${session.latestEventType}_$trigger",
                rootBounds = session.latestRootBounds,
                keywords = session.keywords,
                candidates = listOf(clickedCandidate),
                clickableNodes = emptyList(),
                resultSummary = session.clickedSummary ?: "开屏期间已成功点击跳过按钮"
            )
            return
        }

        val observedNodes = session.clickableNodes.values.toList()
        saveLog(
            packageName = packageName,
            timestamp = session.startTimestamp,
            eventType = "${session.latestEventType}_$trigger",
            rootBounds = session.latestRootBounds,
            keywords = session.keywords,
            candidates = emptyList(),
            clickableNodes = observedNodes,
            resultSummary = if (observedNodes.isEmpty()) {
                "开屏期间未点击成功，且未观察到可点击元素或包含“跳过”文案的元素"
            } else {
                "开屏期间未点击成功，以下为观察到的可点击元素或包含“跳过”文案的元素"
            }
        )
    }

    private fun collectObservedNodes(
        root: AccessibilityNodeInfo,
        rootBounds: Rect,
        session: PendingDetectionSession
    ) {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        val nodesToRecycle = mutableListOf<AccessibilityNodeInfo>()
        queue.addLast(root)

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node !== root && node.isVisibleToUser && shouldLogNode(node)) {
                val signature = buildElementSignature(node)
                session.clickableNodes.putIfAbsent(signature, buildClickableNodeLog(node, rootBounds))
            }

            for (index in 0 until node.childCount) {
                node.getChild(index)?.let { child ->
                    queue.addLast(child)
                    nodesToRecycle.add(child)
                }
            }
        }

        nodesToRecycle.forEach { runCatching { it.recycle() } }
    }

    private fun shouldLogNode(node: AccessibilityNodeInfo): Boolean {
        return node.isClickable || nodeContainsSkipText(node)
    }

    private fun nodeContainsSkipText(node: AccessibilityNodeInfo): Boolean {
        return sequenceOf(node.text?.toString(), node.contentDescription?.toString())
            .filterNotNull()
            .map(::normalizeCandidateText)
            .any(::containsSkipText)
    }

    private fun containsSkipText(text: String): Boolean {
        return "跳过" in text
    }

    private fun buildClickableNodeLog(node: AccessibilityNodeInfo, rootBounds: Rect): ClickableNodeLog {
        val bounds = Rect().also(node::getBoundsInScreen)
        val isReasonableTarget = isReasonableTarget(node, rootBounds)
        val text = node.text?.toString()?.trim()?.takeIf(String::isNotEmpty)
        val contentDescription = node.contentDescription?.toString()?.trim()?.takeIf(String::isNotEmpty)
        val containsSkipText = nodeContainsSkipText(node)
        val note = when {
            node.isClickable && containsSkipText -> "可点击，且包含“跳过”文案"
            !node.isClickable && containsSkipText -> "包含“跳过”文案，但节点不可点击"
            !node.isEnabled -> "节点可点击，但未启用"
            !isReasonableTarget -> "尺寸或位置不符合候选条件"
            else -> "可点击，但未命中关键词或资源提示"
        }

        return ClickableNodeLog(
            text = text,
            contentDescription = contentDescription,
            bounds = "[${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}]",
            className = node.className?.toString(),
            resourceName = node.viewIdResourceName,
            isClickable = node.isClickable,
            containsSkipText = containsSkipText,
            isEnabled = node.isEnabled,
            isReasonableTarget = isReasonableTarget,
            note = note
        )
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
        val clickTarget = findClickTarget(node, rootBounds) ?: return false
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
            .firstOrNull(::containsSkipText)
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
            .replace(Regex("\\s+"), " ")
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
        finalizeDetectionRunnables.values.forEach(handler::removeCallbacks)
        finalizeDetectionRunnables.clear()
        pendingDetectionSessions.clear()
        clickedElementSignatures.clear()
        windowStartTimes.clear()
        Log.i(TAG, "AutoSkip service destroyed")
    }
}

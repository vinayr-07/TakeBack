package com.vr.takeback.domain.security

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

data class NodeBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
    val isValid: Boolean get() = width > 0 && height > 0
}

/**
 * Clean abstraction over AccessibilityNodeInfo for Recents / Overview inspection.
 * Allows pure JVM unit testing of Samsung One UI & Realme UI Recents detection.
 */
interface RecentsNodeSnapshot {
    val text: CharSequence?
    val contentDescription: CharSequence?
    val className: CharSequence?
    val viewIdResourceName: String?
    val bounds: NodeBounds
    val isDismissable: Boolean
    val isClickable: Boolean
    val childCount: Int
    fun getChild(index: Int): RecentsNodeSnapshot?
}

class RealRecentsNodeSnapshot(val node: AccessibilityNodeInfo) : RecentsNodeSnapshot {
    override val text: CharSequence? get() = node.text
    override val contentDescription: CharSequence? get() = node.contentDescription
    override val className: CharSequence? get() = node.className
    override val viewIdResourceName: String? get() = node.viewIdResourceName
    override val bounds: NodeBounds
        get() {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            return NodeBounds(rect.left, rect.top, rect.right, rect.bottom)
        }
    override val isDismissable: Boolean
        get() = node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_DISMISS }
    override val isClickable: Boolean get() = node.isClickable
    override val childCount: Int get() = node.childCount
    override fun getChild(index: Int): RecentsNodeSnapshot? {
        val child = node.getChild(index) ?: return null
        return RealRecentsNodeSnapshot(child)
    }
}

data class MockRecentsNodeSnapshot(
    override val text: CharSequence? = null,
    override val contentDescription: CharSequence? = null,
    override val className: CharSequence? = null,
    override val viewIdResourceName: String? = null,
    override val bounds: NodeBounds = NodeBounds(0, 0, 0, 0),
    override val isDismissable: Boolean = false,
    override val isClickable: Boolean = false,
    val children: List<MockRecentsNodeSnapshot> = emptyList()
) : RecentsNodeSnapshot {
    override val childCount: Int get() = children.size
    override fun getChild(index: Int): RecentsNodeSnapshot? = children.getOrNull(index)
}

data class RecentsCardMatch(
    val matchedLabel: String,
    val cardNode: RecentsNodeSnapshot,
    val cardBounds: NodeBounds,
    val closeButtonNode: RecentsNodeSnapshot? = null
)

/**
 * Pure inspector for Android Recent Apps / Overview screens across AOSP, Samsung One UI,
 * and Realme UI / ColorOS (`com.sec.android.app.launcher`, `com.android.launcher`, `com.oppo.launcher`,
 * `com.oplus.launcher`, `com.android.systemui`).
 */
object RecentsTaskInspector {

    private const val MAX_SEARCH_DEPTH = 16
    private const val MAX_VISITED_NODES = 350

    private val RECENTS_PACKAGES = setOf(
        "com.sec.android.app.launcher",
        "com.android.launcher",
        "com.oppo.launcher",
        "com.oplus.launcher",
        "com.google.android.apps.nexuslauncher",
        "com.android.launcher3",
        "com.android.systemui"
    )

    private val RECENTS_VIEW_ID_MARKERS = listOf(
        "clear_all",
        "btn_clear",
        "recent_container",
        "oplus_clear_all",
        "clear_and_clean",
        "dockview_panel",
        "recents_view",
        "task_view",
        "oplus_recents",
        "oplus_task",
        "snapshot",
        "overview_panel",
        "overview_actions"
    )

    private val RECENTS_EXPLICIT_TEXT_MARKERS = listOf(
        "close all",
        "clear all",
        "no recent items",
        "no recent tasks",
        "no recently used apps"
    )

    fun isLauncherOrRecentsPackage(packageName: String): Boolean {
        val pkg = packageName.lowercase().trim()
        return RECENTS_PACKAGES.contains(pkg) || pkg.contains("launcher")
    }

    /**
     * Returns true ONLY when the active window is genuinely the Recent Apps / Overview task switcher,
     * avoiding false positives on Home Screen app icons or Quick Settings.
     */
    fun isRecentsOverview(
        packageName: String,
        className: String?,
        rootNode: RecentsNodeSnapshot?
    ): Boolean {
        if (!isLauncherOrRecentsPackage(packageName) || rootNode == null) {
            return false
        }
        val cls = (className ?: "").lowercase()
        if (cls.contains("recents") || cls.contains("overview") || cls.contains("taskswitcher")) {
            return true
        }
        return containsRecentsMarker(rootNode, MAX_SEARCH_DEPTH, intArrayOf(0))
    }

    /**
     * Finds a matching Recent Task card for any label in [targetLabels].
     * Ensures the matched target is backed by a task card container (or explicit close/dismiss target)
     * rather than tiny unrelated icons.
     */
    fun findRestrictedTaskCard(
        rootNode: RecentsNodeSnapshot,
        targetLabels: Set<String>,
        screenWidth: Int,
        screenHeight: Int
    ): RecentsCardMatch? {
        val normalizedTargets = targetLabels
            .map { it.trim() }
            .filter { it.length >= 3 }
            .distinct()
        if (normalizedTargets.isEmpty()) return null

        return searchCardRecursive(
            node = rootNode,
            enclosingCard = null,
            targets = normalizedTargets,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            depthRemaining = MAX_SEARCH_DEPTH,
            visitedCounter = intArrayOf(0)
        )
    }

    private fun containsRecentsMarker(
        node: RecentsNodeSnapshot,
        depthRemaining: Int,
        visitedCounter: IntArray
    ): Boolean {
        if (depthRemaining <= 0 || visitedCounter[0] >= MAX_VISITED_NODES) return false
        visitedCounter[0]++

        val viewId = (node.viewIdResourceName ?: "").lowercase()
        if (RECENTS_VIEW_ID_MARKERS.any { viewId.contains(it) }) {
            return true
        }

        val text = (node.text?.toString() ?: "").lowercase().trim()
        val desc = (node.contentDescription?.toString() ?: "").lowercase().trim()
        if (RECENTS_EXPLICIT_TEXT_MARKERS.any { text == it || desc == it }) {
            return true
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (containsRecentsMarker(child, depthRemaining - 1, visitedCounter)) {
                return true
            }
        }
        return false
    }

    private fun isCandidateTaskCard(
        node: RecentsNodeSnapshot,
        screenWidth: Int,
        screenHeight: Int
    ): Boolean {
        val viewId = (node.viewIdResourceName ?: "").lowercase()
        val cls = (node.className?.toString() ?: "").lowercase()
        val b = node.bounds
        val notFullScreen = screenWidth <= 0 || screenHeight <= 0 ||
                b.width < (screenWidth * 0.98f) || b.height < (screenHeight * 0.92f)

        val hasTaskId = viewId.contains("task_view") ||
                viewId.contains("taskview") ||
                viewId.contains("oplus_task") ||
                viewId.contains("snapshot") ||
                cls.contains("taskview")

        if ((hasTaskId || node.isDismissable || hasRecentsCardChild(node)) && b.isValid && notFullScreen) {
            return true
        }

        if (screenWidth > 0 && screenHeight > 0 && b.isValid && notFullScreen) {
            val isCardSized = b.width >= (screenWidth * 0.30f) && b.height >= (screenHeight * 0.20f)
            val isTallCarouselCard = b.height >= (screenHeight * 0.25f) && b.width >= 40 &&
                    (node.isClickable || !node.contentDescription.isNullOrBlank())
            if (isTallCarouselCard ||
                (isCardSized && (viewId.contains("card") || viewId.contains("task") || viewId.contains("recent")))
            ) {
                return true
            }
        }
        return false
    }

    private fun hasRecentsCardChild(node: RecentsNodeSnapshot): Boolean {
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val cid = (child.viewIdResourceName ?: "").lowercase()
            if (cid.contains("oplus_task_header") ||
                cid.contains("task_header") ||
                cid.contains("task_view") ||
                cid.contains("taskview") ||
                cid.contains("snapshot")
            ) {
                return true
            }
        }
        return false
    }

    private fun findCloseButtonChild(node: RecentsNodeSnapshot, depthRemaining: Int = 4): RecentsNodeSnapshot? {
        if (depthRemaining <= 0) return null
        val viewId = (node.viewIdResourceName ?: "").lowercase()
        val text = (node.text?.toString() ?: "").lowercase()
        val desc = (node.contentDescription?.toString() ?: "").lowercase()
        if (node.isClickable && (
                    viewId.contains("close") ||
                    viewId.contains("dismiss") ||
                    text == "close" ||
                    desc.startsWith("close")
                    )
        ) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findCloseButtonChild(child, depthRemaining - 1)
            if (found != null) return found
        }
        return null
    }

    private fun searchCardRecursive(
        node: RecentsNodeSnapshot,
        enclosingCard: RecentsNodeSnapshot?,
        targets: List<String>,
        screenWidth: Int,
        screenHeight: Int,
        depthRemaining: Int,
        visitedCounter: IntArray
    ): RecentsCardMatch? {
        if (depthRemaining <= 0 || visitedCounter[0] >= MAX_VISITED_NODES) return null
        visitedCounter[0]++

        val currentCard = if (isCandidateTaskCard(node, screenWidth, screenHeight)) node else enclosingCard

        val nodeText = node.text?.toString() ?: ""
        val nodeDesc = node.contentDescription?.toString() ?: ""

        for (target in targets) {
            if (matchesLabel(nodeText, target) || matchesLabel(nodeDesc, target)) {
                val chosenCard = currentCard ?: if (node.bounds.isValid &&
                    ((screenWidth <= 0 || node.bounds.width >= screenWidth * 0.30f) ||
                        (screenHeight > 0 && node.bounds.height >= screenHeight * 0.25f && node.bounds.width >= 40))
                ) node else null

                if (chosenCard != null && chosenCard.bounds.isValid) {
                    val closeBtn = findCloseButtonChild(chosenCard)
                    return RecentsCardMatch(
                        matchedLabel = target,
                        cardNode = chosenCard,
                        cardBounds = chosenCard.bounds,
                        closeButtonNode = closeBtn
                    )
                }
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val match = searchCardRecursive(
                node = child,
                enclosingCard = currentCard,
                targets = targets,
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                depthRemaining = depthRemaining - 1,
                visitedCounter = visitedCounter
            )
            if (match != null) return match
        }
        return null
    }

    private fun matchesLabel(haystack: String, target: String): Boolean {
        if (haystack.isBlank()) return false
        val h = haystack.lowercase()
        val t = target.lowercase()
        return h == t ||
                h.startsWith("$t,") ||
                h.startsWith("$t ") ||
                h.endsWith(" $t") ||
                h.contains("close $t") ||
                h.contains(", $t")
    }
}

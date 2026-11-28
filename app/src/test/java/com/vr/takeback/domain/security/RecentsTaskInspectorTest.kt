package com.vr.takeback.domain.security

import org.junit.Assert.*
import org.junit.Test

class RecentsTaskInspectorTest {

    @Test
    fun testSamsungOneUiRecents_detectsOverviewAndFindsRestrictedCards() {
        val instagramCard = MockRecentsNodeSnapshot(
            viewIdResourceName = "com.sec.android.app.launcher:id/task_view",
            contentDescription = "Instagram",
            bounds = NodeBounds(100, 300, 980, 1800),
            isDismissable = true,
            children = listOf(
                MockRecentsNodeSnapshot(
                    text = "Instagram",
                    bounds = NodeBounds(150, 310, 450, 360)
                )
            )
        )
        val root = MockRecentsNodeSnapshot(
            children = listOf(
                instagramCard,
                MockRecentsNodeSnapshot(
                    viewIdResourceName = "com.sec.android.app.launcher:id/clear_all_button",
                    text = "Close all",
                    bounds = NodeBounds(350, 1950, 730, 2060)
                )
            )
        )

        assertTrue(
            RecentsTaskInspector.isRecentsOverview(
                packageName = "com.sec.android.app.launcher",
                className = "com.android.quickstep.RecentsActivity",
                rootNode = root
            )
        )

        val match = RecentsTaskInspector.findRestrictedTaskCard(
            rootNode = root,
            targetLabels = setOf("Instagram", "Settings"),
            screenWidth = 1080,
            screenHeight = 2400
        )
        assertNotNull("Must locate Instagram card on Samsung Recents", match)
        assertEquals("Instagram", match!!.matchedLabel)
        assertEquals(instagramCard.bounds, match.cardBounds)
    }

    @Test
    fun testRealmeUiRecents_detectsClearAllAndSettingsCard() {
        val settingsCard = MockRecentsNodeSnapshot(
            viewIdResourceName = "com.android.launcher:id/oplus_task_view",
            bounds = NodeBounds(120, 340, 960, 1780),
            isDismissable = true,
            children = listOf(
                MockRecentsNodeSnapshot(
                    contentDescription = "Settings",
                    bounds = NodeBounds(160, 350, 420, 400)
                )
            )
        )
        val root = MockRecentsNodeSnapshot(
            children = listOf(
                settingsCard,
                MockRecentsNodeSnapshot(
                    viewIdResourceName = "com.android.launcher:id/btn_clear",
                    text = "Clear all",
                    bounds = NodeBounds(400, 1980, 680, 2100)
                )
            )
        )

        assertTrue(
            RecentsTaskInspector.isRecentsOverview(
                packageName = "com.android.launcher",
                className = "com.android.launcher3.uioverrides.QuickstepLauncher",
                rootNode = root
            )
        )

        val match = RecentsTaskInspector.findRestrictedTaskCard(
            rootNode = root,
            targetLabels = setOf("Settings"),
            screenWidth = 1080,
            screenHeight = 2400
        )
        assertNotNull("Must locate Settings card on Realme Recents", match)
        assertEquals("Settings", match!!.matchedLabel)
    }

    @Test
    fun testHomeLauncherScreen_isNotTreatedAsRecents() {
        val homeScreen = MockRecentsNodeSnapshot(
            children = listOf(
                MockRecentsNodeSnapshot(
                    text = "Settings",
                    bounds = NodeBounds(80, 400, 240, 560),
                    isClickable = true
                ),
                MockRecentsNodeSnapshot(
                    text = "Instagram",
                    bounds = NodeBounds(300, 400, 460, 560),
                    isClickable = true
                )
            )
        )

        assertFalse(
            "Home launcher icons must NEVER trigger Recents overview detection",
            RecentsTaskInspector.isRecentsOverview(
                packageName = "com.android.launcher",
                className = "com.android.launcher3.uioverrides.QuickstepLauncher",
                rootNode = homeScreen
            )
        )
    }

    @Test
    fun testRealmeUiActualHierarchy_emptyIdCardWithOplusHeaderAndPeekingWidth_detected() {
        val peekingAdminCard = MockRecentsNodeSnapshot(
            contentDescription = "Device administration settings",
            bounds = NodeBounds(0, 298, 168, 1783),
            isClickable = true,
            children = listOf(
                MockRecentsNodeSnapshot(
                    viewIdResourceName = "com.android.launcher:id/oplus_task_header",
                    bounds = NodeBounds(0, 298, 168, 438)
                ),
                MockRecentsNodeSnapshot(
                    viewIdResourceName = "com.android.launcher:id/snapshot",
                    bounds = NodeBounds(0, 438, 168, 1783)
                )
            )
        )
        val root = MockRecentsNodeSnapshot(
            viewIdResourceName = "com.android.launcher:id/recent_container",
            children = listOf(
                MockRecentsNodeSnapshot(
                    viewIdResourceName = "com.android.launcher:id/overview_panel",
                    contentDescription = "No recent tasks",
                    children = listOf(peekingAdminCard)
                ),
                MockRecentsNodeSnapshot(
                    viewIdResourceName = "com.android.launcher:id/oplus_clear_all_panel",
                    children = listOf(
                        MockRecentsNodeSnapshot(
                            viewIdResourceName = "com.android.launcher:id/btn_clear",
                            text = "Close",
                            contentDescription = "Close",
                            bounds = NodeBounds(312, 2089, 768, 2221),
                            isClickable = true
                        )
                    )
                )
            )
        )

        assertTrue(
            RecentsTaskInspector.isRecentsOverview(
                packageName = "com.android.launcher",
                className = "com.android.launcher.Launcher",
                rootNode = root
            )
        )

        val match = RecentsTaskInspector.findRestrictedTaskCard(
            rootNode = root,
            targetLabels = setOf("Accessibility", "Device administration settings", "App info"),
            screenWidth = 1080,
            screenHeight = 2377
        )
        assertNotNull("Must detect peeking Device administration card in Realme Overview", match)
        assertEquals("Device administration settings", match!!.matchedLabel)
        assertEquals(peekingAdminCard.bounds, match.cardBounds)
    }

    @Test
    fun testRealmeUiPeekingCardWithoutNotImportantChildren_stillDetected() {
        // Without FLAG_INCLUDE_NOT_IMPORTANT_VIEWS, Realme strips oplus_task_header/snapshot children
        val peekingCardWithoutChildren = MockRecentsNodeSnapshot(
            contentDescription = "App info",
            bounds = NodeBounds(0, 296, 168, 1785),
            isClickable = true
        )
        val dockButton = MockRecentsNodeSnapshot(
            contentDescription = "App info Button",
            bounds = NodeBounds(188, 1909, 292, 2013),
            isClickable = true
        )
        val root = MockRecentsNodeSnapshot(
            viewIdResourceName = "com.android.launcher:id/recent_container",
            children = listOf(
                MockRecentsNodeSnapshot(
                    viewIdResourceName = "com.android.launcher:id/overview_panel",
                    children = listOf(peekingCardWithoutChildren)
                ),
                MockRecentsNodeSnapshot(
                    viewIdResourceName = "com.android.launcher:id/dockview_panel",
                    children = listOf(dockButton)
                ),
                MockRecentsNodeSnapshot(
                    viewIdResourceName = "com.android.launcher:id/btn_clear",
                    text = "Close",
                    bounds = NodeBounds(312, 2089, 768, 2221),
                    isClickable = true
                )
            )
        )

        val match = RecentsTaskInspector.findRestrictedTaskCard(
            rootNode = root,
            targetLabels = setOf("App info"),
            screenWidth = 1080,
            screenHeight = 2377
        )
        assertNotNull("Must detect tall peeking card even when child wrappers are stripped", match)
        assertEquals(peekingCardWithoutChildren.bounds, match!!.cardBounds)
    }
}

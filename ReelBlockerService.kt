package com.example.reelblocker

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

class ReelBlockerService : AccessibilityService() {

    private var lastTriggerTime: Long = 0
    private val DEBOUNCE_DELAY = 1200L // 1.2 second debounce

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val rootNode = rootInActiveWindow ?: return
        val packageName = event?.packageName?.toString() ?: return

        when (packageName) {
            "com.instagram.android" -> handleInstagram(rootNode)
            "com.facebook.katana", "com.facebook.lite" -> handleFacebook(rootNode)
        }
    }

    private fun handleInstagram(rootNode: AccessibilityNodeInfo) {
        // Detects Reels Viewer Tab and Clips containers
        val reelNodes = rootNode.findAccessibilityNodeInfosByViewId("com.instagram.android:id/clips_video_container")
        val reelTabSelected = rootNode.findAccessibilityNodeInfosByText("Reels")

        if (reelNodes.isNotEmpty() || hasReelInContentDescription(rootNode)) {
            blockAction("Instagram Reel Blocked!")
        }
    }

    private fun handleFacebook(rootNode: AccessibilityNodeInfo) {
        // Detects Facebook Reels indicators
        val reelElements = rootNode.findAccessibilityNodeInfosByText("Reels and short videos")
        val reelsTab = rootNode.findAccessibilityNodeInfosByText("Reels")

        if (reelElements.isNotEmpty() || hasReelInContentDescription(rootNode)) {
            blockAction("Facebook Reel Blocked!")
        }
    }

    private fun hasReelInContentDescription(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        if (desc.contains("reel") || desc.contains("reels")) return true

        for (i in 0 until node.childCount) {
            if (hasReelInContentDescription(node.getChild(i))) return true
        }
        return false
    }

    private fun blockAction(message: String) {
        val now = System.currentTimeMillis()
        if (now - lastTriggerTime > DEBOUNCE_DELAY) {
            lastTriggerTime = now
            // Automatically trigger Back button to kick the user out of the Reel
            performGlobalAction(GLOBAL_ACTION_BACK)
            Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onInterrupt() {}
}
package com.junior.assistant.service

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class AccessibilityHelperService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: AccessibilityHelperService? = null
            private set

        fun isServiceRunning(): Boolean = instance != null
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d("AccessibilityService", "AccessibilityHelperService initialized")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d("AccessibilityService", "AccessibilityHelperService connected")
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No-op
    }

    override fun onInterrupt() {
        // No-op
    }

    fun scrollDown(): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        return performScrollAction(rootNode, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
    }

    fun scrollUp(): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        return performScrollAction(rootNode, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
    }

    private fun performScrollAction(node: AccessibilityNodeInfo, action: Int): Boolean {
        if (node.isScrollable && (node.actions and action) != 0) {
            val success = node.performAction(action)
            node.recycle()
            return success
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                if (performScrollAction(child, action)) {
                    node.recycle()
                    return true
                }
            }
        }
        node.recycle()
        return false
    }

    fun scrapeScreenText(): String {
        val rootNode = rootInActiveWindow ?: return "Screen is blank or unreadable, Sir."
        val builder = java.lang.StringBuilder()
        extractTextFromNode(rootNode, builder)
        rootNode.recycle()
        val text = builder.toString().trim()
        return if (text.isNotEmpty()) text else "I couldn't detect any text on the screen, Sir."
    }

    private fun extractTextFromNode(node: AccessibilityNodeInfo?, builder: java.lang.StringBuilder) {
        if (node == null) return
        val text = node.text?.toString() ?: node.contentDescription?.toString()
        if (!text.isNullOrEmpty()) {
            builder.append(text).append("\n")
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                extractTextFromNode(child, builder)
                child.recycle()
            }
        }
    }

    fun goBack(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_BACK)
    }

    fun goHome(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_HOME)
    }

    fun clickOnText(target: String): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        val found = rootNode.findAccessibilityNodeInfosByText(target)
        if (!found.isNullOrEmpty()) {
            for (node in found) {
                if (node.isClickable) {
                    val res = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    rootNode.recycle()
                    return res
                } else {
                    var parent = node.parent
                    while (parent != null) {
                        if (parent.isClickable) {
                            val res = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            rootNode.recycle()
                            return res
                        }
                        parent = parent.parent
                    }
                }
            }
        }
        rootNode.recycle()
        return false
    }

    fun typeText(targetText: String): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        val focusedNode = rootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focusedNode != null) {
            val arguments = android.os.Bundle()
            arguments.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                targetText
            )
            val res = focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            focusedNode.recycle()
            rootNode.recycle()
            return res
        }
        rootNode.recycle()
        return false
    }
}

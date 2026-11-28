package com.example.jarvis

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class JarvisAccessibility : AccessibilityService() {

    companion object {
        var instance: JarvisAccessibility? = null
    }

    override fun onServiceConnected() { instance = this }
    override fun onDestroy() { instance = null; super.onDestroy() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    // ---------- Жести ----------

    /** dir: up/down/left/right - куди рухається ПАЛЕЦЬ (швидкий свайп) */
    fun swipe(dir: String) {
        val m = resources.displayMetrics
        val w = m.widthPixels.toFloat()
        val h = m.heightPixels.toFloat()
        val cx = w / 2
        val cy = h / 2
        val dy = h * 0.27f
        val dx = w * 0.32f
        val (sx, sy, ex, ey) = when (dir) {
            "up" -> listOf(cx, cy + dy, cx, cy - dy)
            "down" -> listOf(cx, cy - dy, cx, cy + dy)
            "left" -> listOf(cx + dx, cy, cx - dx, cy)
            else -> listOf(cx - dx, cy, cx + dx, cy)
        }
        val path = Path().apply { moveTo(sx, sy); lineTo(ex, ey) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 180))
            .build()
        dispatchGesture(g, null, null)
    }

    fun tapPx(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        dispatchGesture(g, null, null)
    }

    fun tapFraction(fx: Float, fy: Float) {
        val m = resources.displayMetrics
        tapPx(m.widthPixels * fx, m.heightPixels * fy)
    }

    // ---------- Пошук елементів на екрані ----------

    fun rootNodes(): List<AccessibilityNodeInfo> {
        val list = mutableListOf<AccessibilityNodeInfo>()
        try {
            for (w in windows) {
                val r = w.root
                if (r != null) list.add(r)
            }
        } catch (e: Exception) {
        }
        if (list.isEmpty()) {
            val r = rootInActiveWindow
            if (r != null) list.add(r)
        }
        return list
    }

    fun findNodes(text: String): List<AccessibilityNodeInfo> {
        val out = mutableListOf<AccessibilityNodeInfo>()
        for (r in rootNodes()) {
            val found = r.findAccessibilityNodeInfosByText(text)
            if (found != null) out.addAll(found)
        }
        return out
    }

    fun clickNode(n: AccessibilityNodeInfo?): Boolean {
        var cur = n
        var depth = 0
        while (cur != null && depth < 8) {
            if (cur.isClickable && cur.isEnabled) {
                return cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            cur = cur.parent
            depth++
        }
        if (n != null) {
            val r = Rect()
            n.getBoundsInScreen(r)
            if (r.width() > 0 && r.height() > 0) {
                tapPx(r.exactCenterX(), r.exactCenterY())
                return true
            }
        }
        return false
    }

    /** Натиснути перший елемент, у тексті/описі якого є одне з слів. */
    fun clickAnyText(names: List<String>, skipEditable: Boolean = true): Boolean {
        for (name in names) {
            val node = findNodes(name).firstOrNull { !(skipEditable && it.isEditable) }
            if (node != null && clickNode(node)) return true
        }
        return false
    }

    // ---------- Введення тексту ----------

    private fun firstEditable(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (n.isEditable) return n
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            val f = firstEditable(c)
            if (f != null) return f
        }
        return null
    }

    fun typeText(text: String): Boolean {
        for (r in rootNodes()) {
            val candidates = mutableListOf<AccessibilityNodeInfo>()
            val inputFocus = r.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (inputFocus != null) candidates.add(inputFocus)
            val editable = firstEditable(r)
            if (editable != null) candidates.add(editable)
            for (f in candidates) {
                val old = if (f.isShowingHintText) "" else (f.text?.toString() ?: "")
                val args = Bundle()
                args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, old + text)
                if (f.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return true
                // запасний варіант: вставка з буфера обміну
                try {
                    val cm = getSystemService(ClipboardManager::class.java)
                    cm.setPrimaryClip(ClipData.newPlainText("jarvis", text))
                    f.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                    if (f.performAction(AccessibilityNodeInfo.ACTION_PASTE)) return true
                } catch (e: Exception) {
                }
            }
        }
        return false
    }

    fun pressSend(): Boolean {
        if (clickAnyText(listOf("Надіслати", "Відправити", "Send"), true)) return true
        for (r in rootNodes()) {
            val f = r.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: firstEditable(r)
            if (f != null) {
                return f.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            }
        }
        return false
    }

    // ---------- Bluetooth через швидкі налаштування ----------

    private fun stateOf(n: AccessibilityNodeInfo): String {
        val sd = n.stateDescription?.toString() ?: ""
        val cd = n.contentDescription?.toString() ?: ""
        val tx = n.text?.toString() ?: ""
        return (sd + " " + cd + " " + tx).lowercase()
    }

    /** desired: true - увімкнути, false - вимкнути, null - перемкнути. Повертає "on", "off" або "" якщо не вдалось. */
    fun setBluetooth(desired: Boolean?): String {
        performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
        Thread.sleep(1600)
        var tile: AccessibilityNodeInfo? = null
        for (name in listOf("Bluetooth", "Блютуз")) {
            tile = findNodes(name).firstOrNull { !it.isEditable }
            if (tile != null) break
        }
        if (tile == null) {
            performGlobalAction(GLOBAL_ACTION_BACK)
            return ""
        }
        var st = ""
        var cur: AccessibilityNodeInfo? = tile
        var depth = 0
        while (cur != null && depth < 4) {
            st += " " + stateOf(cur)
            cur = cur.parent
            depth++
        }
        val isOn = st.contains("увімкнено") || Regex("\\bon\\b").containsMatchIn(st)
        val isOff = st.contains("вимкнено") || Regex("\\boff\\b").containsMatchIn(st)
        val want = desired ?: !isOn
        val already = (want && isOn) || (!want && isOff)
        if (!already) clickNode(tile)
        Thread.sleep(800)
        performGlobalAction(GLOBAL_ACTION_BACK)
        return if (want) "on" else "off"
    }

    // ---------- Камера ----------

    fun clickShutter() {
        val names = listOf(
            "Затвор", "Shutter", "Зробити фото", "Take photo",
            "Записати відео", "Record video", "Зупинити запис", "Stop recording"
        )
        if (!clickAnyText(names, true)) tapFraction(0.5f, 0.87f)
    }
}

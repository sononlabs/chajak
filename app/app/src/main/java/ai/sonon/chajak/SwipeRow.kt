package ai.sonon.chajak

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * 왼쪽으로 밀면 뒤에 깔린 동작 버튼(삭제)이 오른쪽에 드러나는 줄. 오른쪽으로 밀거나 줄을 누르면 닫힌다.
 * 안의 버튼(녹음 듣기 등)은 그대로 눌리고, 옆으로 끄는 동작만 가로챈다. 한 번에 한 줄만 열린다.
 */
@SuppressLint("ViewConstructor")
class SwipeRow(context: Context, private val content: View, private val action: View, private val reveal: Int) : FrameLayout(context) {

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var startTx = 0f
    private var dragging = false
    var enabledSwipe = true

    init {
        addView(action, LayoutParams(reveal, LayoutParams.MATCH_PARENT, android.view.Gravity.END))
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        action.visibility = INVISIBLE
    }

    val isOpen get() = content.translationX < 0f

    fun close(animate: Boolean = true) {
        if (animate) content.animate().translationX(0f).setDuration(160).withEndAction { action.visibility = INVISIBLE }.start()
        else { content.translationX = 0f; action.visibility = INVISIBLE }
        if (opened === this) opened = null
    }

    private fun open() {
        opened?.takeIf { it !== this }?.close()
        opened = this
        action.visibility = VISIBLE
        content.animate().translationX(-reveal.toFloat()).setDuration(160).start()
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        if (!enabledSwipe) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; startTx = content.translationX; dragging = false
                // 열린 줄을 누르면 안의 버튼 대신 닫기만 한다(삭제 버튼 자리는 제외)
                if (isOpen && e.x < width - reveal) return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.x - downX
                if (abs(dx) > slop && abs(dx) > abs(e.y - downY) * 1.5f) {
                    dragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
        }
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!enabledSwipe) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_MOVE -> {
                val dx = e.x - downX
                if (!dragging && abs(dx) > slop) { dragging = true; parent?.requestDisallowInterceptTouchEvent(true) }
                if (dragging) {
                    action.visibility = VISIBLE
                    content.translationX = (startTx + dx).coerceIn(-reveal.toFloat(), 0f)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) { if (content.translationX < -reveal / 2f) open() else close() }
                else if (isOpen) close()
                dragging = false
            }
        }
        return true
    }

    companion object {
        // 화면을 다시 그리면 옛 줄은 버려지므로 붙잡아 두지 않는다
        private var openedRef = java.lang.ref.WeakReference<SwipeRow>(null)
        private var opened: SwipeRow?
            get() = openedRef.get()
            set(v) { openedRef = java.lang.ref.WeakReference(v) }
    }
}

package dev.kuass.ivlyrics

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.core.widget.NestedScrollView

class ManualScrollView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : NestedScrollView(ctx, attrs) {
    var onManualTouch: ((Boolean) -> Unit)? = null
    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        val intercepted = super.onInterceptTouchEvent(event)
        if (intercepted) onManualTouch?.invoke(true)
        return intercepted
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> onManualTouch?.invoke(true)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> onManualTouch?.invoke(false)
        }
        return super.onTouchEvent(event)
    }
    override fun performClick(): Boolean = super.performClick()
}

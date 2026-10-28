package li.songe.gkd.rescue

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

object RescueUi {
    val ink = Color.rgb(24, 54, 54)
    val muted = Color.rgb(69, 96, 95)
    val blue = Color.rgb(18, 108, 114)
    val background = Color.rgb(243, 248, 247)

    fun dp(context: Context, value: Int) =
        (value * context.resources.displayMetrics.density + .5f).toInt()

    fun text(context: Context, value: String, size: Float, bold: Boolean = false) =
        TextView(context).apply {
            text = value
            textSize = size
            setTextColor(if (bold) ink else muted)
            setLineSpacing(dp(context, 3).toFloat(), 1.08f)
            if (bold) setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        }

    fun button(context: Context, value: String, primary: Boolean = false) =
        Button(context).apply {
            text = value
            textSize = 26f
            isAllCaps = false
            isSingleLine = false
            gravity = Gravity.CENTER
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setPadding(dp(context, 18), dp(context, 16), dp(context, 18), dp(context, 16))
            minHeight = dp(context, 76)
            applyButtonStyle(this, primary)
        }

    fun applyButtonStyle(button: Button, primary: Boolean) {
        button.setTextColor(if (primary) Color.WHITE else blue)
        button.background = RippleDrawable(ColorStateList.valueOf(0x28000000),
            GradientDrawable().apply {
                setColor(if (primary) blue else Color.rgb(227, 240, 238))
                cornerRadius = dp(button.context, 20).toFloat()
            }, null)
    }

    fun space(parent: LinearLayout, height: Int) {
        parent.addView(View(parent.context), LinearLayout.LayoutParams(-1, dp(parent.context, height)))
    }
}

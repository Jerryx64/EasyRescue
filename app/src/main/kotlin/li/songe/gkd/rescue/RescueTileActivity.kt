package li.songe.gkd.rescue

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper

class RescueTileActivity : Activity() {
    private var dispatched = false
    override fun onResume() {
        super.onResume()
        if (dispatched) return
        dispatched = true
        val ready = RescueController.isReady()
        if (!ready || Build.VERSION.SDK_INT >= 31) {
            startActivity(Intent(this, ElderHomeActivity::class.java)
                .putExtra(ElderHomeActivity.EXTRA_RESCUE, ready)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            finish()
        } else {
            finish()
            Handler(Looper.getMainLooper()).postDelayed({ RescueController.requestRescue() }, 150L)
        }
    }
}

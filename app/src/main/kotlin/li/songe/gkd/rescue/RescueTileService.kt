package li.songe.gkd.rescue

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import li.songe.gkd.R

class RescueTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply { label = getString(R.string.elder_tile_label); state = Tile.STATE_INACTIVE; updateTile() }
    }

    override fun onClick() {
        super.onClick()
        if (isLocked) unlockAndRun { openRescue() } else openRescue()
    }

    @Suppress("DEPRECATION")
    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openRescue() {
        val target = if (RescueController.isReady()) RescueTileActivity::class.java else ElderHomeActivity::class.java
        val intent = Intent(this, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        else startActivityAndCollapse(intent)
    }
}

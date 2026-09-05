package dev.curfew.app.enforce

import android.content.Intent
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.curfew.app.R
import dev.curfew.app.curfew
import dev.curfew.app.ui.MainActivity
import kotlinx.coroutines.launch

/**
 * A quick settings tile that says whether Curfew is blocking anything.
 *
 * It reports and it opens the app. It does not toggle: a tile is two taps from any lock screen,
 * and a session that could be ended from there would make every lock in the app decorative. The
 * tile being inert is the feature.
 */
class CurfewTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        val runtime = curfew
        runtime.scope.launch {
            val running = runCatching { runtime.activeProfiles.value }.getOrDefault(emptyList())
            val tile = qsTile ?: return@launch
            tile.state = if (running.isEmpty()) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
            tile.label = getString(R.string.app_name)
            tile.icon = Icon.createWithResource(this@CurfewTileService, R.drawable.ic_tile)
            tile.contentDescription = if (running.isEmpty()) {
                getString(R.string.tile_idle)
            } else {
                running.joinToString(", ")
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                tile.subtitle = if (running.isEmpty()) {
                    getString(R.string.tile_idle)
                } else {
                    running.joinToString(", ")
                }
            }
            tile.updateTile()
        }
    }

    /** Tapping opens Curfew, because everything worth doing needs a screen to do it on. */
    @Suppress("DEPRECATION")
    override fun onClick() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                android.app.PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    android.app.PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        } else {
            startActivityAndCollapse(intent)
        }
    }

    companion object {
        /** Ask the system to call [onStartListening] again, after the state has moved. */
        fun refresh(context: android.content.Context) {
            requestListeningState(
                context,
                android.content.ComponentName(context, CurfewTileService::class.java),
            )
        }
    }
}

/*
 * Acknowledgment:
 * Portions of this code are adapted from XClipper by Kaustubh Patange.
 * Licensed under the Apache License 2.0.
 */

package sefirah.clipboard

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import sefirah.domain.interfaces.NetworkManager
import sefirah.domain.interfaces.DeviceManager
import sefirah.domain.model.ClipboardInfo
import javax.inject.Inject

@AndroidEntryPoint
class ClipboardChangeActivity : FragmentActivity() {
    @Inject lateinit var networkManager: NetworkManager

    @Inject lateinit var deviceManager: DeviceManager

    @Inject lateinit var clipboardEventTracker: ClipboardEventTracker

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        val clipboardManager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        lifecycleScope.launch {
            /** Seems like adding a delay is giving [ClipboardManager] time to capture
             *  clipboard text.
             */
            delay(250)
            if (hasFocus) {
                val data = clipboardManager.primaryClip
                    ?.takeIf { it.itemCount > 0 }
                    ?.getItemAt(0)
                    ?.coerceToText(this@ClipboardChangeActivity)
                    ?.toString()
                val eventId = data?.let(clipboardEventTracker::recordLocalText)
                if (data != null && eventId != null) {
                    networkManager.sendClipboardMessage(
                        ClipboardInfo(
                            clipboardType = "text/plain",
                            content = data,
                            eventId = eventId,
                            originDeviceId = deviceManager.localDevice.deviceId,
                        ),
                    )
                }
                finish()
            }
        }
        super.onWindowFocusChanged(hasFocus)
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        onWindowFocusChanged(true)
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    companion object {
        fun launch(context: Context) = with(context) {
            val intent = Intent(this, ClipboardChangeActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        }
    }
}

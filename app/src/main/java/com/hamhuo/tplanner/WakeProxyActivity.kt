package com.hamhuo.tplanner

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Invisible proxy that delegates a watch wake-up signal to [MainActivity].
 *
 * Copied from Samsung Health's DeeplinkDelegatorActivity pattern observed in logcat:
 *   1. This Activity is Theme.Translucent.NoTitleBar — the user never sees it.
 *   2. It runs in its own taskAffinity (":proxy") so it never pollutes the main task.
 *   3. It prefers [Intent.FLAG_ACTIVITY_REORDER_TO_FRONT] — if MainActivity is already
 *      alive, bring its task to the front WITHOUT destroying the backstack.
 *      User keeps whatever they were doing (editing a journal entry, viewing insights, …).
 *   4. If REORDER_TO_FRONT is blocked (Samsung BAL / rare ROM), falls back to
 *      NEW_TASK | CLEAR_TASK as a last resort.
 *   5. Calls [finish] immediately — the proxy lives for < 50 ms.
 *
 * Wake-up signal arrives from [WakeDataLayerService] (Data Layer / GMS).
 */
class WakeProxyActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val wakeFromWatch = intent.getBooleanExtra(EXTRA_WAKE_FROM_WATCH, false)
        Log.d(TAG, "onCreate: wakeFromWatch=$wakeFromWatch")

        val target = Intent(this, MainActivity::class.java).apply {
            // Prefer REORDER_TO_FRONT: bring existing task to front, keep backstack intact.
            // SINGLE_TOP ensures onNewIntent() fires if MainActivity is already on top.
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
            if (wakeFromWatch) {
                putExtra(MainActivity.EXTRA_WAKE_FROM_WATCH, true)
            }
        }

        try {
            startActivity(target)
            Log.d(TAG, "onCreate: MainActivity launched with REORDER_TO_FRONT")
        } catch (balBlock: SecurityException) {
            // Samsung BAL checker blocked REORDER_TO_FRONT (rare when overlay is
            // attached, but possible if user revoked overlay permission).
            Log.w(TAG, "onCreate: REORDER_TO_FRONT blocked by BAL, falling back to CLEAR_TASK", balBlock)
            target.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            try {
                startActivity(target)
            } catch (e: Exception) {
                Log.e(TAG, "onCreate: even CLEAR_TASK fallback failed", e)
            }
        } catch (e: Exception) {
            Log.e(TAG, "onCreate: startActivity failed", e)
        }

        // Proxy done — disappear.  The user sees MainActivity (which was already
        // alive and just got brought to front) or a fresh launch.
        finish()

        // Detach the BAL-bypass overlay that WakeDataLayerService attached.
        // Must post to the process-scoped main Looper, NOT window.decorView —
        // finish() above destroys the Activity's Handler thread before the
        // 2 s delay fires, causing "Handler on a dead thread" crashes on Samsung.
        Handler(Looper.getMainLooper()).postDelayed({
            WakeDataLayerService.detachOverlayFromProxy()
        }, 2_000L)
    }

    companion object {
        private const val TAG = "TplannerWakeProxy"
        const val EXTRA_WAKE_FROM_WATCH = MainActivity.EXTRA_WAKE_FROM_WATCH
    }
}

package com.hamhuo.tplanner

import android.app.Activity
import android.os.Bundle

/**
 * Minimal launcher placeholder.  The app is primarily accessed through
 * its seven watch faces; this Activity exists only so the APK has a
 * valid launcher entry point.
 */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
    }
}

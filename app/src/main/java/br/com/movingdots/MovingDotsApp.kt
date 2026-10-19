package br.com.movingdots

import android.app.Application

/** Migrates old settings and lets the build's AutoDetection check its state on every process start. */
class MovingDotsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs(this).migrate()
        AutoDetection.onProcessStart(this)
    }
}

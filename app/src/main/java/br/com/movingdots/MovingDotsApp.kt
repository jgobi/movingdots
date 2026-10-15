package br.com.movingdots

import android.app.Application

/** Lets the build's AutoDetection repair Automatic mode on every process start. */
class MovingDotsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AutoDetection.onProcessStart(this)
    }
}

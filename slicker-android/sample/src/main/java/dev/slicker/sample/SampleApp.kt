package dev.slicker.sample

import android.app.Application
import dev.slicker.Slicker

class SampleApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Slicker.start(this)
    }
}

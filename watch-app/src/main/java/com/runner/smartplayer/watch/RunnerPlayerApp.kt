package com.runner.smartplayer.watch

import android.app.Application
import com.runner.smartplayer.watch.state.AppGraph

class RunnerPlayerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppGraph.initialize(this)
    }
}

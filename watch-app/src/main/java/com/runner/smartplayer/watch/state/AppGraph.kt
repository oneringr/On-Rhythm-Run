package com.runner.smartplayer.watch.state

import android.content.Context
import com.runner.smartplayer.watch.data.LibraryScanner
import com.runner.smartplayer.watch.data.ManifestRepository

object AppGraph {
    private lateinit var appContext: Context

    lateinit var manifestRepository: ManifestRepository
        private set

    val uiStateStore: UiStateStore by lazy { UiStateStore() }

    fun initialize(context: Context) {
        appContext = context.applicationContext
        manifestRepository = ManifestRepository(LibraryScanner())
    }

    fun requireContext(): Context = appContext
}

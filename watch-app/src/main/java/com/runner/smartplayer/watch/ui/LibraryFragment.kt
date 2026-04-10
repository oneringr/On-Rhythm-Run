package com.runner.smartplayer.watch.ui

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.runner.smartplayer.watch.R
import com.runner.smartplayer.watch.player.ServiceIntents
import com.runner.smartplayer.watch.state.AppGraph
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class LibraryFragment : Fragment(R.layout.fragment_library) {
    private val formatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")
        .withZone(ZoneId.systemDefault())

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val totalTracksView = view.findViewById<TextView>(R.id.totalTracksValue)
        val calmTracksView = view.findViewById<TextView>(R.id.calmTracksValue)
        val excitedTracksView = view.findViewById<TextView>(R.id.excitedTracksValue)
        val lastImportView = view.findViewById<TextView>(R.id.lastImportValue)
        val pathView = view.findViewById<TextView>(R.id.libraryPathValue)
        val rescanButton = view.findViewById<MaterialButton>(R.id.rescanButton)

        rescanButton.setOnClickListener {
            ServiceIntents.send(requireContext(), ServiceIntents.ACTION_RESCAN)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                AppGraph.uiStateStore.state.collect { state ->
                    totalTracksView.text = state.librarySummary.totalTracks.toString()
                    calmTracksView.text = state.librarySummary.calmTracks.toString()
                    excitedTracksView.text = state.librarySummary.excitedTracks.toString()
                    lastImportView.text = state.librarySummary.lastImportedAt?.let { formatter.format(it) } ?: "--"
                    pathView.text = state.librarySummary.exportDirectory.ifBlank { "未发现导出曲库" }
                }
            }
        }
    }
}

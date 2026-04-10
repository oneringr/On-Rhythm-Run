package com.runner.smartplayer.watch.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.button.MaterialButton
import com.runner.smartplayer.watch.R
import com.runner.smartplayer.watch.player.ServiceIntents
import com.runner.smartplayer.watch.state.AppGraph

class MainActivity : AppCompatActivity() {
    private lateinit var viewPager: ViewPager2
    private lateinit var playerButton: MaterialButton
    private lateinit var runModeButton: MaterialButton
    private lateinit var libraryButton: MaterialButton

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grantResults ->
        if (grantResults[Manifest.permission.READ_EXTERNAL_STORAGE] == false) {
            AppGraph.uiStateStore.setMessage("未授予存储权限，无法扫描 /sdcard/Music 中的曲库")
        }
        ServiceIntents.send(this, ServiceIntents.ACTION_BOOT)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        viewPager = findViewById(R.id.mainPager)
        playerButton = findViewById(R.id.playerTabButton)
        runModeButton = findViewById(R.id.runModeTabButton)
        libraryButton = findViewById(R.id.libraryTabButton)

        viewPager.adapter = MainPagerAdapter(this)
        viewPager.offscreenPageLimit = 3
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateTabSelection(position)
            }
        })

        playerButton.setOnClickListener { viewPager.currentItem = 0 }
        runModeButton.setOnClickListener { viewPager.currentItem = 1 }
        libraryButton.setOnClickListener { viewPager.currentItem = 2 }
        updateTabSelection(0)

        val missingPermissions = buildList {
            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BODY_SENSORS) != PackageManager.PERMISSION_GRANTED) {
                add(Manifest.permission.BODY_SENSORS)
            }
            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }

        if (missingPermissions.isNotEmpty()) {
            permissionLauncher.launch(missingPermissions.toTypedArray())
        } else {
            ServiceIntents.send(this, ServiceIntents.ACTION_BOOT)
        }
    }

    private fun updateTabSelection(position: Int) {
        playerButton.isChecked = position == 0
        runModeButton.isChecked = position == 1
        libraryButton.isChecked = position == 2
    }
}

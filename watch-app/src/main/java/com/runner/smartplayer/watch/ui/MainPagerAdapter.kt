package com.runner.smartplayer.watch.ui

import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter

class MainPagerAdapter(
    activity: AppCompatActivity,
) : FragmentStateAdapter(activity) {
    override fun getItemCount(): Int = 3

    override fun createFragment(position: Int): Fragment = when (position) {
        0 -> PlayerFragment()
        1 -> RunModeFragment()
        else -> LibraryFragment()
    }
}

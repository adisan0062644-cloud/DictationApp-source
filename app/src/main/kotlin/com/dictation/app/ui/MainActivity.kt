package com.dictation.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.dictation.app.R
import com.dictation.app.databinding.ActivityMainBinding
import com.dictation.app.services.FloatingService
import com.google.android.material.tabs.TabLayout

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val historyFragment = HistoryFragment()
    private val settingsFragment = SettingsFragment()

    private val notifPerm = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> startOverlayService() }

    private val micPerm = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> ensureOverlayThenStart() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = showFragment(tab.position)
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
        if (savedInstanceState == null) {
            showFragment(0)
        }

        binding.btnGrantOverlay.setOnClickListener {
            requestOverlay()
        }

        ensurePermissionsThenStart()
    }

    override fun onResume() {
        super.onResume()
        updateOverlayButton()
        // Если FloatingService уже работает, обновим кнопку
        binding.btnGrantOverlay.visibility =
            if (canDrawOverlay()) android.view.View.GONE else android.view.View.VISIBLE
    }

    private fun showFragment(idx: Int) {
        val f: Fragment = if (idx == 0) historyFragment else settingsFragment
        supportFragmentManager.beginTransaction()
            .replace(R.id.container, f)
            .commit()
    }

    private fun updateOverlayButton() {
        binding.btnGrantOverlay.visibility =
            if (canDrawOverlay()) android.view.View.GONE else android.view.View.VISIBLE
    }

    private fun canDrawOverlay(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(this)
        else true

    private fun ensurePermissionsThenStart() {
        // 1) Уведомления (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) {
            notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        ensureOverlayThenStart()
    }

    private fun ensureOverlayThenStart() {
        // 2) Overlay
        if (!canDrawOverlay()) {
            requestOverlay()
            return
        }
        // 3) Микрофон
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            micPerm.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        startOverlayService()
    }

    private fun startOverlayService() {
        FloatingService.start(this)
        updateOverlayButton()
    }

    private fun requestOverlay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val i = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName"))
            startActivity(i)
        }
    }
}
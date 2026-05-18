package com.soundboard

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "Soundboard"
    }

    private lateinit var ipText: TextView
    private lateinit var rateLimitSwitch: Switch
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: RecentSoundsAdapter
    private lateinit var focusStateText: TextView
    private lateinit var focusVotesText: TextView
    private lateinit var focusTimerText: TextView
    private lateinit var focusPolicyText: TextView

    private var soundService: SoundService? = null
    private var isBound = false
    private val handler = Handler(Looper.getMainLooper())

    private val recentSoundsListener: () -> Unit = {
        handler.post {
            adapter.submitList(RecentSoundsManager.getRecentSounds())
        }
    }

    private val focusTimeListener: () -> Unit = {
        handler.post {
            updateFocusTimeDisplay()
        }
    }

    private val focusTimerRunnable = object : Runnable {
        override fun run() {
            updateFocusTimeDisplay()
            if (FocusTimeManager.getState() == FocusTimeManager.State.FOCUS_VOTING) {
                handler.postDelayed(this, 1000)
            }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.d(TAG, "Service connected")
            val binder = service as SoundService.LocalBinder
            soundService = binder.getService()
            isBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d(TAG, "Service disconnected")
            soundService = null
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        ipText = findViewById(R.id.ipText)
        rateLimitSwitch = findViewById(R.id.rateLimitSwitch)
        rateLimitSwitch.setOnCheckedChangeListener { _, isChecked ->
            RateLimitManager.setEnabled(isChecked)
        }
        recyclerView = findViewById(R.id.recentSoundsRecyclerView)
        focusStateText = findViewById(R.id.focusStateText)
        focusVotesText = findViewById(R.id.focusVotesText)
        focusTimerText = findViewById(R.id.focusTimerText)
        focusPolicyText = findViewById(R.id.focusPolicyText)

        RecentSoundsManager.init(applicationContext)

        adapter = RecentSoundsAdapter { sound ->
            soundService?.playSound(sound.filename)
        }
        recyclerView.layoutManager = GridLayoutManager(this, 3)
        recyclerView.adapter = adapter

        adapter.submitList(RecentSoundsManager.getRecentSounds())
        RecentSoundsManager.addChangeListener(recentSoundsListener)
        FocusTimeManager.addChangeListener(focusTimeListener)

        requestNotificationPermission()
        displayIpAddress()

        // Auto-start the server
        startSoundService()
    }

    override fun onStart() {
        super.onStart()
        Intent(this, SoundService::class.java).also { intent ->
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        RecentSoundsManager.removeChangeListener(recentSoundsListener)
        FocusTimeManager.removeChangeListener(focusTimeListener)
        handler.removeCallbacks(focusTimerRunnable)
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    1
                )
            }
        }
    }

    private fun startSoundService() {
        Log.d(TAG, "startSoundService called, isBound=$isBound")
        val intent = Intent(this, SoundService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Log.d(TAG, "Starting foreground service")
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            if (!isBound) {
                bindService(intent, connection, Context.BIND_AUTO_CREATE)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start service", e)
        }
    }

    private fun updateFocusTimeDisplay() {
        val status = FocusTimeManager.getStatus()
        if (status.state == FocusTimeManager.State.DEFAULT) {
            focusStateText.text = "Focus Time: Inactive"
            focusVotesText.visibility = View.GONE
            focusTimerText.visibility = View.GONE
            focusPolicyText.visibility = View.GONE
            handler.removeCallbacks(focusTimerRunnable)
        } else {
            focusStateText.text = "Focus Time: VOTING"
            focusVotesText.text = "For ${status.votesFor} / Against ${status.votesAgainst}"
            val min = status.remainingSeconds / 60
            val sec = status.remainingSeconds % 60
            focusTimerText.text = String.format("%d:%02d", min, sec)
            focusPolicyText.text = when (status.playPolicy) {
                FocusTimeManager.PlayPolicy.BLOCKED -> "Sounds BLOCKED"
                FocusTimeManager.PlayPolicy.UNLIMITED -> "Sounds UNLIMITED"
                FocusTimeManager.PlayPolicy.FOCUS_LIMITED ->
                    "Limited: ${status.focusPlaysLimit} plays per user"
                FocusTimeManager.PlayPolicy.NORMAL -> ""
            }
            focusVotesText.visibility = View.VISIBLE
            focusTimerText.visibility = View.VISIBLE
            focusPolicyText.visibility = if (focusPolicyText.text.isEmpty()) View.GONE else View.VISIBLE
            handler.removeCallbacks(focusTimerRunnable)
            handler.postDelayed(focusTimerRunnable, 1000)
        }
    }

    private fun displayIpAddress() {
        val ip = getLocalIpAddress()
        if (ip != null) {
            ipText.text = "http://$ip:8080/ui"
        } else {
            ipText.text = "Connect to WiFi to get IP address"
        }
    }

    private fun getLocalIpAddress(): String? {
        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val wifiInfo = wifiManager.connectionInfo
            val ipInt = wifiInfo.ipAddress
            if (ipInt != 0) {
                return String.format(
                    "%d.%d.%d.%d",
                    ipInt and 0xff,
                    ipInt shr 8 and 0xff,
                    ipInt shr 16 and 0xff,
                    ipInt shr 24 and 0xff
                )
            }

            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is Inet4Address) {
                        return address.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }
}

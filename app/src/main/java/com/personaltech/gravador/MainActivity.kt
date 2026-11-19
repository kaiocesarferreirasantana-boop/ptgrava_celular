package com.personaltech.gravador

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var rgMode: RadioGroup
    private lateinit var rgFps: RadioGroup
    private lateinit var rgBitrate: RadioGroup
    private lateinit var cbMic: CheckBox
    private lateinit var cbCountdown: CheckBox
    private lateinit var cbMinimize: CheckBox
    private lateinit var btnRec: Button
    private lateinit var tvStatus: TextView

    private val prefs by lazy { getSharedPreferences("cfg", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        rgMode = findViewById(R.id.rgMode)
        rgFps = findViewById(R.id.rgFps)
        rgBitrate = findViewById(R.id.rgBitrate)
        cbMic = findViewById(R.id.cbMic)
        cbCountdown = findViewById(R.id.cbCountdown)
        cbMinimize = findViewById(R.id.cbMinimize)
        btnRec = findViewById(R.id.btnRec)
        tvStatus = findViewById(R.id.tvStatus)

        // Preferências salvas
        rgMode.check(if (prefs.getBoolean("fill", true)) R.id.modeFill else R.id.modeFit)
        rgFps.check(if (prefs.getInt("fps", 30) == 60) R.id.fps60 else R.id.fps30)
        rgBitrate.check(
            when (prefs.getInt("br", 12)) { 8 -> R.id.br8; 20 -> R.id.br20; else -> R.id.br12 }
        )
        cbMic.isChecked = prefs.getBoolean("mic", false)
        cbCountdown.isChecked = prefs.getBoolean("countdown", true)
        cbMinimize.isChecked = prefs.getBoolean("minimize", true)

        btnRec.setOnClickListener {
            if (RecorderService.state != RecorderService.State.IDLE) {
                startService(Intent(this, RecorderService::class.java).setAction(RecorderService.ACTION_STOP))
            } else {
                savePrefs()
                askPermissionsThenCapture()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        RecorderService.listener = { updateUi() }
        updateUi()
    }

    override fun onPause() {
        RecorderService.listener = null
        super.onPause()
    }

    private fun savePrefs() {
        prefs.edit()
            .putBoolean("fill", rgMode.checkedRadioButtonId == R.id.modeFill)
            .putInt("fps", if (rgFps.checkedRadioButtonId == R.id.fps60) 60 else 30)
            .putInt("br", when (rgBitrate.checkedRadioButtonId) { R.id.br8 -> 8; R.id.br20 -> 20; else -> 12 })
            .putBoolean("mic", cbMic.isChecked)
            .putBoolean("countdown", cbCountdown.isChecked)
            .putBoolean("minimize", cbMinimize.isChecked)
            .apply()
    }

    private fun askPermissionsThenCapture() {
        val needed = mutableListOf<String>()
        if (cbMic.isChecked && !granted(Manifest.permission.RECORD_AUDIO))
            needed += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS))
            needed += Manifest.permission.POST_NOTIFICATIONS
        if (needed.isEmpty()) launchCapture()
        else requestPermissions(needed.toTypedArray(), REQ_PERMS)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code != REQ_PERMS) return
        if (cbMic.isChecked && !granted(Manifest.permission.RECORD_AUDIO)) {
            cbMic.isChecked = false
            savePrefs()
            Toast.makeText(this, "Sem permissão do microfone: gravando sem áudio.", Toast.LENGTH_LONG).show()
        }
        launchCapture()
    }

    private fun launchCapture() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CAPTURE) return
        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, "Captura cancelada.", Toast.LENGTH_SHORT).show()
            return
        }
        val i = Intent(this, RecorderService::class.java).apply {
            action = RecorderService.ACTION_START
            putExtra(RecorderService.EXTRA_RESULT_CODE, resultCode)
            putExtra(RecorderService.EXTRA_DATA, data)
            putExtra(RecorderService.EXTRA_FILL, rgMode.checkedRadioButtonId == R.id.modeFill)
            putExtra(RecorderService.EXTRA_FPS, if (rgFps.checkedRadioButtonId == R.id.fps60) 60 else 30)
            putExtra(
                RecorderService.EXTRA_BITRATE,
                when (rgBitrate.checkedRadioButtonId) {
                    R.id.br8 -> 8_000_000; R.id.br20 -> 20_000_000; else -> 12_000_000
                }
            )
            putExtra(RecorderService.EXTRA_MIC, cbMic.isChecked)
            putExtra(RecorderService.EXTRA_COUNTDOWN, cbCountdown.isChecked)
        }
        startForegroundService(i)
        if (cbMinimize.isChecked) moveTaskToBack(true)
    }

    private fun updateUi() {
        val st = RecorderService.state
        val idle = st == RecorderService.State.IDLE
        btnRec.text = if (idle) "● GRAVAR" else "■ PARAR"
        tvStatus.text = when (st) {
            RecorderService.State.IDLE -> "Pronto. Saída: 1080×1920 MP4 (H.264)"
            RecorderService.State.COUNTDOWN -> "Começando…"
            RecorderService.State.RECORDING -> "Gravando…"
        }
        val enable = idle
        listOf<View>(rgMode, rgFps, rgBitrate, cbMic, cbCountdown, cbMinimize).forEach { v ->
            v.isEnabled = enable
            if (v is RadioGroup) for (k in 0 until v.childCount) v.getChildAt(k).isEnabled = enable
        }
    }

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val REQ_PERMS = 10
        private const val REQ_CAPTURE = 11
    }
}

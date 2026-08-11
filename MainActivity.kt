package com.isaque.signalplay

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.google.android.material.snackbar.Snackbar
import com.isaque.signalplay.databinding.ActivityMainBinding
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var libVlc: LibVLC
    private lateinit var player: MediaPlayer
    private lateinit var recents: RecentStreams
    private var fullscreen = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        recents = RecentStreams(getSharedPreferences("signal_play", MODE_PRIVATE))
        libVlc = LibVLC(this, arrayListOf("--network-caching=1000", "--clock-jitter=0", "--clock-synchro=0"))
        player = MediaPlayer(libVlc).also {
            it.attachViews(binding.videoLayout, null, false, false)
            it.setEventListener(::onPlayerEvent)
        }

        bindActions()
    }

    private fun bindActions() = with(binding) {
        playButton.setOnClickListener { openSignal() }
        stopButton.setOnClickListener { stopSignal() }
        historyButton.setOnClickListener { showHistory() }
        fullscreenButton.setOnClickListener { setFullscreen(!fullscreen) }
        urlInput.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_GO) { openSignal(); true } else false
        }
        protocolGroup.setOnCheckedStateChangeListener { _, checked ->
            val preset = when (checked.firstOrNull()) {
                R.id.chipHls -> "https://servidor.exemplo/canal/playlist.m3u8"
                R.id.chipRtmp -> "rtmp://servidor.exemplo/live/canal"
                R.id.chipRtp -> "rtp://@:5004"
                R.id.chipUdp -> "udp://@:5000"
                else -> "srt://0.0.0.0:9000?mode=listener"
            }
            urlInput.setText(preset)
            urlInput.setSelection(preset.length)
            helperText.text = when (checked.firstOrNull()) {
                R.id.chipSrt -> "Para receber SRT, use mode=listener. Para conectar, use mode=caller."
                R.id.chipHls -> "Cole o endereço completo da playlist .m3u8."
                R.id.chipRtmp -> "Cole o endereço RTMP completo, incluindo a chave quando houver."
                R.id.chipRtp -> "Use @ para escutar em todas as interfaces, por exemplo rtp://@:5004."
                R.id.chipUdp -> "Use @ para escutar em todas as interfaces, por exemplo udp://@:5000."
                else -> ""
            }
        }
    }

    private fun openSignal() {
        binding.urlLayout.error = null
        StreamAddress.validate(binding.urlInput.text?.toString().orEmpty())
            .onFailure { binding.urlLayout.error = it.message }
            .onSuccess { address ->
                player.stop()
                val media = Media(libVlc, android.net.Uri.parse(address))
                media.setHWDecoderEnabled(true, false)
                media.addOption(":network-caching=1000")
                player.media = media
                media.release()
                recents.add(address)
                binding.emptyState.isVisible = false
                binding.playButton.isEnabled = false
                binding.stopButton.isEnabled = true
                setStatus("CONECTANDO", false)
                player.play()
            }
    }

    private fun stopSignal() {
        player.stop()
        binding.emptyState.isVisible = true
        binding.playButton.isEnabled = true
        binding.stopButton.isEnabled = false
        setStatus("PARADO", false)
    }

    private fun onPlayerEvent(event: MediaPlayer.Event) = runOnUiThread {
        when (event.type) {
            MediaPlayer.Event.Playing -> { setStatus("NO AR", true); binding.playButton.isEnabled = true }
            MediaPlayer.Event.Buffering -> if (event.buffering < 100f) setStatus("BUFFER ${event.buffering.toInt()}%", false)
            MediaPlayer.Event.EncounteredError -> {
                binding.playButton.isEnabled = true
                binding.stopButton.isEnabled = false
                setStatus("SEM SINAL", false)
                Snackbar.make(binding.root, "Não foi possível abrir o sinal. Confira o endereço e a rede.", Snackbar.LENGTH_LONG).show()
            }
            MediaPlayer.Event.EndReached -> stopSignal()
        }
    }

    private fun setStatus(text: String, live: Boolean) {
        binding.statusBadge.text = text
        binding.statusBadge.setTextColor(getColor(if (live) R.color.signal else R.color.muted))
    }

    private fun showHistory() {
        val items = recents.get()
        if (items.isEmpty()) {
            Snackbar.make(binding.root, "Nenhum endereço recente.", Snackbar.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this).setTitle("Sinais recentes")
            .setItems(items.toTypedArray()) { _, index -> binding.urlInput.setText(items[index]) }
            .setNegativeButton("Fechar", null).show()
    }

    private fun setFullscreen(enabled: Boolean) {
        fullscreen = enabled
        binding.controlPanel.isVisible = !enabled
        if (enabled) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
            window.insetsController?.show(WindowInsets.Type.systemBars())
        }
    }

    override fun onBackPressed() {
        if (fullscreen) setFullscreen(false) else super.onBackPressed()
    }

    override fun onDestroy() {
        player.stop(); player.detachViews(); player.release(); libVlc.release()
        super.onDestroy()
    }
}

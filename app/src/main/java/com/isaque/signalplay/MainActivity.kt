package com.isaque.signalplay

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.View
import android.view.SurfaceHolder
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.google.android.material.snackbar.Snackbar
import com.isaque.signalplay.databinding.ActivityMainBinding
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var libVlc: LibVLC
    private lateinit var player: MediaPlayer
    private lateinit var hlsPlayer: ExoPlayer
    private lateinit var recents: RecentStreams
    private lateinit var srtPlayer: SrtPlayer
    private var fullscreen = false
    private var pendingSrtAddress: String? = null
    private var diagnostics = "Nenhum sinal analisado ainda."
    private var activeEngine = Engine.NONE
    private val timeoutHandler = Handler(Looper.getMainLooper())
    private val connectionTimeout = Runnable {
        if (activeEngine != Engine.NONE && binding.statusBadge.text != "NO AR") {
            showPlaybackError("Tempo limite de 15 segundos. O servidor não entregou áudio ou vídeo.")
        }
    }

    private enum class Engine { NONE, VLC, HLS, SRT }

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
        hlsPlayer = ExoPlayer.Builder(this).build().also {
            binding.hlsPlayerView.player = it
            it.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    when (state) {
                        Player.STATE_BUFFERING -> setStatus("BUFFER", false)
                        Player.STATE_READY -> if (it.playWhenReady) markPlaying()
                        Player.STATE_ENDED -> stopSignal()
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    showPlaybackError("HLS: ${error.errorCodeName}. ${error.cause?.message ?: error.message}")
                }
            })
        }
        srtPlayer = SrtPlayer(this, object : SrtPlayer.Listener {
            override fun onSrtState(state: String, detail: String) = runOnUiThread {
                diagnostics = appendDiagnostic("Estado SRT: $state\n$detail")
                when (state) {
                    "CONNECTING" -> setStatus("CONECTANDO", false)
                    "BUFFERING" -> setStatus("BUFFER", false)
                    "PLAYING" -> markPlaying()
                    "ENDED" -> stopSignal()
                    "ERROR" -> showPlaybackError("SRT: $detail")
                }
            }

            override fun onSrtDiagnostics(report: String) = runOnUiThread {
                diagnostics = appendDiagnostic(report)
            }
        })
        binding.srtSurface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                pendingSrtAddress?.let {
                    pendingSrtAddress = null
                    srtPlayer.play(normalizeSrtAddress(it), holder.surface)
                }
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
            override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
        })

        bindActions()
    }

    private fun bindActions() = with(binding) {
        playButton.setOnClickListener { openSignal() }
        stopButton.setOnClickListener { stopSignal() }
        historyButton.setOnClickListener { showHistory() }
        diagnosticsButton.setOnClickListener { showDiagnostics() }
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
        binding.diagnosticText.isVisible = false
        StreamAddress.validate(binding.urlInput.text?.toString().orEmpty())
            .onFailure { binding.urlLayout.error = it.message }
            .onSuccess { address ->
                stopPlayers()
                recents.add(address)
                binding.emptyState.isVisible = false
                binding.playButton.isEnabled = false
                binding.stopButton.isEnabled = true
                setStatus("CONECTANDO", false)
                timeoutHandler.removeCallbacks(connectionTimeout)
                timeoutHandler.postDelayed(connectionTimeout, 15_000)
                diagnostics = "Protocolo: ${address.substringBefore(":").uppercase()}\nEndereço: ${maskAddress(address)}"
                if (address.startsWith("srt://", ignoreCase = true)) {
                    openSrt(address)
                } else if (address.substringBefore(":").lowercase() in setOf("http", "https")) {
                    openHls(address)
                } else {
                    openVlc(address)
                }
            }
    }

    private fun openSrt(address: String) {
        activeEngine = Engine.SRT
        binding.videoLayout.isVisible = false
        binding.hlsPlayerView.isVisible = false
        binding.srtSurface.isVisible = true
        if (binding.srtSurface.holder.surface.isValid) {
            srtPlayer.play(normalizeSrtAddress(address), binding.srtSurface.holder.surface)
        } else {
            pendingSrtAddress = address
        }
    }

    private fun openHls(address: String) {
        activeEngine = Engine.HLS
        binding.videoLayout.isVisible = false
        binding.hlsPlayerView.isVisible = true
        val item = ExoMediaItem.Builder()
            .setUri(address)
            .setMimeType(MimeTypes.APPLICATION_M3U8)
            .build()
        hlsPlayer.setMediaItem(item)
        hlsPlayer.prepare()
        hlsPlayer.playWhenReady = true
    }

    private fun openVlc(address: String) {
        activeEngine = Engine.VLC
        binding.hlsPlayerView.isVisible = false
        binding.srtSurface.isVisible = false
        binding.videoLayout.isVisible = true
        val media = Media(libVlc, android.net.Uri.parse(address)).apply {
            setHWDecoderEnabled(true, false)
            addOption(":network-caching=1200")
            addOption(":live-caching=1200")
            addOption(":clock-jitter=0")
            addOption(":clock-synchro=0")
        }
        player.media = media
        media.release()
        player.play()
    }

    private fun stopSignal() {
        stopPlayers()
        binding.emptyState.isVisible = true
        binding.playButton.isEnabled = true
        binding.stopButton.isEnabled = false
        setStatus("PARADO", false)
    }

    private fun stopPlayers() {
        timeoutHandler.removeCallbacks(connectionTimeout)
        if (::player.isInitialized) player.stop()
        if (::hlsPlayer.isInitialized) hlsPlayer.stop()
        if (::srtPlayer.isInitialized) srtPlayer.stop()
        pendingSrtAddress = null
        activeEngine = Engine.NONE
    }

    private fun onPlayerEvent(event: MediaPlayer.Event) = runOnUiThread {
        when (event.type) {
            MediaPlayer.Event.Playing -> markPlaying()
            MediaPlayer.Event.Buffering -> if (event.buffering < 100f) setStatus("BUFFER ${event.buffering.toInt()}%", false)
            MediaPlayer.Event.EncounteredError -> showPlaybackError(
                "LibVLC não abriu o sinal. Confira modo, IP, porta, passphrase e se o emissor já está ativo."
            )
            MediaPlayer.Event.EndReached -> stopSignal()
        }
    }

    private fun markPlaying() {
        timeoutHandler.removeCallbacks(connectionTimeout)
        binding.playButton.isEnabled = true
        binding.stopButton.isEnabled = true
        binding.diagnosticText.isVisible = false
        setStatus("NO AR", true)
    }

    private fun showPlaybackError(message: String) {
        timeoutHandler.removeCallbacks(connectionTimeout)
        binding.playButton.isEnabled = true
        binding.stopButton.isEnabled = false
        binding.diagnosticText.text = message
        binding.diagnosticText.isVisible = true
        setStatus("SEM SINAL", false)
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
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

    private fun showDiagnostics() {
        AlertDialog.Builder(this)
            .setTitle("Diagnóstico do sinal")
            .setMessage(diagnostics)
            .setPositiveButton("Copiar") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Signal Play diagnóstico", diagnostics))
            }
            .setNegativeButton("Fechar", null)
            .show()
    }

    private fun appendDiagnostic(text: String): String = "$diagnostics\n\n$text".takeLast(12_000)

    private fun maskAddress(address: String): String = address
        .replace(Regex("(?i)(passphrase=)[^&]+"), "${'$'}1••••••••")
        .replace(Regex("(?i)(token=)[^&]+"), "${'$'}1••••••••")

    private fun normalizeSrtAddress(address: String): String {
        if (!address.contains(Regex("(?i)[?&]mode=listener(?:&|$)"))) return address
        return address.replace(Regex("(?i)^srt://(?:0\.0\.0\.0|@)(?=:)"), "srt://")
    }

    private fun setFullscreen(enabled: Boolean) {
        fullscreen = enabled
        binding.controlPanel.isVisible = !enabled
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        if (enabled) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
            insetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
            insetsController.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onBackPressed() {
        if (fullscreen) setFullscreen(false) else super.onBackPressed()
    }

    override fun onDestroy() {
        timeoutHandler.removeCallbacksAndMessages(null)
        hlsPlayer.release()
        srtPlayer.release()
        player.stop(); player.detachViews(); player.release(); libVlc.release()
        super.onDestroy()
    }
}

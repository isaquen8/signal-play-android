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
import android.widget.EditText
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
    private lateinit var favorites: FavoriteStreams
    private lateinit var srtPlayer: SrtPlayer
    private var fullscreen = false
    private var pendingSrtAddress: String? = null
    private var pendingSrtDeJitterMs = 200
    private var activePrivateAddress: String? = null
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

        val preferences = getSharedPreferences("signal_play", MODE_PRIVATE)
        recents = RecentStreams(preferences)
        favorites = FavoriteStreams(preferences)
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
                diagnostics = appendDiagnostic("Estado SRT: $state\n${sanitizeDiagnostic(detail)}")
                when (state) {
                    "CONNECTING" -> setStatus("CONECTANDO", false)
                    "BUFFERING" -> setStatus("BUFFER", false)
                    "MEDIA", "PLAYING" -> markPlaying()
                    "ENDED" -> stopSignal()
                    "ERROR" -> showPlaybackError("SRT: ${sanitizeDiagnostic(detail)}")
                }
            }

            override fun onSrtDiagnostics(report: String) = runOnUiThread {
                diagnostics = appendDiagnostic(sanitizeDiagnostic(report))
            }
        })
        binding.srtSurface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                pendingSrtAddress?.let {
                    pendingSrtAddress = null
                    srtPlayer.play(normalizeSrtAddress(it), holder.surface, pendingSrtDeJitterMs)
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
        favoritesButton.setOnClickListener { showFavorites() }
        saveFavoriteButton.setOnClickListener { saveFavorite() }
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
                else -> "srt://servidor.exemplo:9000"
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
            srtOptionsPanel.isVisible = checked.firstOrNull() == R.id.chipSrt
        }
        srtModeGroup.setOnCheckedStateChangeListener { _, checked ->
            helperText.text = if (checked.firstOrNull() == R.id.chipListener)
                "Listener: o aparelho aguarda uma conexão na porta informada."
            else "Caller: o aparelho conecta ao endereço remoto informado."
        }
    }

    private fun openSignal() {
        binding.urlLayout.error = null
        binding.diagnosticText.isVisible = false
        StreamAddress.validate(binding.urlInput.text?.toString().orEmpty())
            .onFailure { binding.urlLayout.error = it.message }
            .onSuccess { address ->
                val finalAddress = if (address.startsWith("srt://", true)) configuredSrtAddress(address) else address
                stopPlayers()
                activePrivateAddress = finalAddress
                recents.add(finalAddress)
                binding.emptyState.isVisible = false
                binding.playButton.isEnabled = false
                binding.stopButton.isEnabled = true
                setStatus("CONECTANDO", false)
                timeoutHandler.removeCallbacks(connectionTimeout)
                timeoutHandler.postDelayed(connectionTimeout, 15_000)
                diagnostics = "Protocolo: ${finalAddress.substringBefore(":").uppercase()}\nEndereço: ${privateAddressLabel(finalAddress)}"
                if (finalAddress.startsWith("srt://", ignoreCase = true)) {
                    openSrt(finalAddress, selectedDeJitterMs())
                } else if (finalAddress.substringBefore(":").lowercase() in setOf("http", "https")) {
                    openHls(finalAddress)
                } else {
                    openVlc(finalAddress)
                }
            }
    }

    private fun openSrt(address: String, deJitterMs: Int) {
        activeEngine = Engine.SRT
        binding.videoLayout.isVisible = false
        binding.hlsPlayerView.isVisible = false
        binding.srtSurface.isVisible = true
        if (binding.srtSurface.holder.surface.isValid) {
            srtPlayer.play(normalizeSrtAddress(address), binding.srtSurface.holder.surface, deJitterMs)
        } else {
            pendingSrtAddress = address
            pendingSrtDeJitterMs = deJitterMs
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
        activePrivateAddress = null
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
            .setItems(items.map(::privateAddressLabel).toTypedArray()) { _, index -> loadAddress(items[index]) }
            .setNegativeButton("Fechar", null).show()
    }

    private fun saveFavorite() {
        val raw = binding.urlInput.text?.toString().orEmpty().trim()
        StreamAddress.validate(raw).onFailure {
            binding.urlLayout.error = it.message
        }.onSuccess { address ->
            val nameInput = EditText(this).apply { hint = "Ex.: Estúdio 1" }
            AlertDialog.Builder(this)
                .setTitle("Salvar como favorito")
                .setView(nameInput)
                .setPositiveButton("Salvar") { _, _ ->
                    val name = nameInput.text.toString().trim()
                    if (name.isBlank()) {
                        Snackbar.make(binding.root, "Informe um nome para o favorito.", Snackbar.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    val protocol = address.substringBefore(":").uppercase()
                    favorites.save(FavoriteStream(
                        name = name,
                        address = if (protocol == "SRT") configuredSrtAddress(address) else address,
                        protocol = protocol,
                        srtMode = selectedSrtMode(),
                        latencyMs = selectedLatencyMs(),
                        deJitterMs = selectedDeJitterMs()
                    ))
                    Snackbar.make(binding.root, "Favorito salvo: $name", Snackbar.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }
    }

    private fun showFavorites() {
        val items = favorites.get()
        if (items.isEmpty()) {
            Snackbar.make(binding.root, "Nenhum favorito salvo.", Snackbar.LENGTH_SHORT).show()
            return
        }
        val labels = items.map { "${it.name}  •  ${it.protocol}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Favoritos")
            .setItems(labels) { _, index -> loadFavorite(items[index]) }
            .setNeutralButton("Excluir…") { _, _ -> showDeleteFavorite(items) }
            .setNegativeButton("Fechar", null)
            .show()
    }

    private fun showDeleteFavorite(items: List<FavoriteStream>) {
        AlertDialog.Builder(this)
            .setTitle("Excluir favorito")
            .setItems(items.map { it.name }.toTypedArray()) { _, index ->
                favorites.remove(items[index].name)
                Snackbar.make(binding.root, "Favorito excluído.", Snackbar.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun loadFavorite(item: FavoriteStream) {
        selectProtocol(item.protocol)
        binding.urlInput.setText(item.address)
        binding.urlInput.setSelection(item.address.length)
        binding.srtLatencyInput.setText(item.latencyMs.toString())
        binding.srtDeJitterInput.setText(item.deJitterMs.toString())
        binding.srtModeGroup.check(if (item.srtMode == "listener") R.id.chipListener else R.id.chipCaller)
    }

    private fun loadAddress(address: String) {
        selectProtocol(address.substringBefore(":").uppercase())
        binding.urlInput.setText(address)
        binding.urlInput.setSelection(address.length)
    }

    private fun selectProtocol(protocol: String) {
        val chip = when (protocol.uppercase()) {
            "HLS", "HTTP", "HTTPS" -> R.id.chipHls
            "RTMP" -> R.id.chipRtmp
            "RTP" -> R.id.chipRtp
            "UDP" -> R.id.chipUdp
            else -> R.id.chipSrt
        }
        binding.protocolGroup.check(chip)
        binding.srtOptionsPanel.isVisible = chip == R.id.chipSrt
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

    private fun privateAddressLabel(address: String): String =
        "${address.substringBefore(":").uppercase()} •••••••• (endereço oculto)"

    private fun sanitizeDiagnostic(text: String): String {
        var result = text
        activePrivateAddress?.let { result = result.replace(it, "••••••••") }
        return result.replace(
            Regex("(?i)(srt|https?|rtmp|rtp|udp)://[^\\s]+"),
            "${'$'}1://••••••••"
        )
    }

    private fun selectedSrtMode(): String =
        if (binding.srtModeGroup.checkedChipId == R.id.chipListener) "listener" else "caller"

    private fun selectedLatencyMs(): Int = binding.srtLatencyInput.text?.toString()
        ?.toIntOrNull()?.coerceIn(0, 60_000) ?: 125

    private fun selectedDeJitterMs(): Int = binding.srtDeJitterInput.text?.toString()
        ?.toIntOrNull()?.coerceIn(0, 10_000) ?: 200

    private fun configuredSrtAddress(address: String): String {
        val base = address.substringBefore("?")
        val existing = address.substringAfter("?", "").split("&")
            .filter { it.isNotBlank() }
            .filterNot {
                val key = it.substringBefore("=")
                key.equals("mode", true) || key.equals("latency", true)
            }
        val mode = selectedSrtMode()
        val modeBase = if (mode == "listener")
            base.replace(Regex("""(?i)^srt://[^/:?#]*(?=:\d+)"""), "srt://")
        else base
        val query = existing + listOf("mode=$mode", "latency=${selectedLatencyMs()}")
        return "$modeBase?${query.joinToString("&")}"
    }

    private fun normalizeSrtAddress(address: String): String {
        if (!address.contains(Regex("(?i)[?&]mode=listener(?:&|$)"))) return address
        return address.replace(Regex("""(?i)^srt://(?:0\.0\.0\.0|@)(?=:)"""), "srt://")
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

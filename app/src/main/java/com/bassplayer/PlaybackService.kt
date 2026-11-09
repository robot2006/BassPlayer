package com.bassplayer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media3.common.AudioAttributes as ExoAudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.media3.exoplayer.source.ShuffleOrder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat

@UnstableApi
class PlaybackService : Service(), AudioManager.OnAudioFocusChangeListener {

    interface Listener {
        fun onStateChanged(state: PlaybackSnapshot)
        fun onProgress(positionMs: Long, durationMs: Long)
        fun onError(message: String)
    }

    data class PlaybackSnapshot(
        val song: Song?,
        val index: Int,
        val queueSize: Int,
        val isPlaying: Boolean,
        val isPrepared: Boolean,
        val positionMs: Long,
        val durationMs: Long,
        val audioSessionId: Int,
        val shuffle: Boolean,
        val repeatMode: Int
    )

    inner class LocalBinder : Binder() {
        fun setListener(listener: Listener?) {
            this@PlaybackService.listener = listener
        }

        fun playQueue(items: List<Song>, startIndex: Int) {
            this@PlaybackService.playQueue(items, startIndex)
        }

        fun toggle() = this@PlaybackService.toggle()

        fun next() = this@PlaybackService.skip(1)

        fun previous() = this@PlaybackService.skip(-1)

        fun seekTo(positionMs: Long) = this@PlaybackService.seekTo(positionMs)

        fun setShuffle(enabled: Boolean) = this@PlaybackService.setShuffle(enabled)

        fun cycleRepeat() = this@PlaybackService.cycleRepeat()

        fun snapshot(): PlaybackSnapshot = this@PlaybackService.snapshot()
    }

    companion object {
        const val REPEAT_OFF = 0
        const val REPEAT_ALL = 1
        const val REPEAT_ONE = 2

        private const val CHANNEL_ID = "bass_player_playback"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_TOGGLE = "com.bassplayer.action.TOGGLE"
        private const val ACTION_NEXT = "com.bassplayer.action.NEXT"
        private const val ACTION_PREV = "com.bassplayer.action.PREV"
        private const val ACTION_STOP = "com.bassplayer.action.STOP"

        private const val SESSION_ACTIONS =
            PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_SEEK_TO or
                PlaybackStateCompat.ACTION_STOP
    }

    private val binder = LocalBinder()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioManager by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }
    private val isEnglish: Boolean
        get() = getSharedPreferences("bass_player_prefs", MODE_PRIVATE)
            .getString("app_language", null)?.equals("en", true) == true

    private var listener: Listener? = null
    private var session: MediaSessionCompat? = null
    private var player: ExoPlayer? = null
    private var focusRequest: AudioFocusRequest? = null

    private var queue: List<Song> = emptyList()
    private var index = -1
    private var prepared = false
    private var playing = false
    private var wantsPlay = false
    private var resumeOnFocusGain = false
    private var durationMs = 0L
    private var artBitmap: Bitmap? = null
    private var foregroundStarted = false
    private var releasing = false
    private var shuffleEnabled = false
    private var repeatMode = REPEAT_OFF

    private val ticker = object : Runnable {
        override fun run() {
            val current = player ?: return
            val position = try {
                current.currentPosition.coerceAtLeast(0L)
            } catch (_: Exception) {
                0L
            }
            listener?.onProgress(position, durationMs)
            updateSessionState(position)
            if (current.isPlaying || current.playbackState == Player.STATE_BUFFERING) {
                mainHandler.postDelayed(this, 500)
            }
        }
    }

    private val currentSong: Song?
        get() = queue.getOrNull(index)

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> {
                    prepared = true
                    val exo = player ?: return
                    val reported = exo.duration
                    if (reported > 0) durationMs = reported
                    updateMetadata()
                    updateSessionState()
                    ensureForeground()
                    notifyState()
                    startTicker()
                }

                Player.STATE_BUFFERING -> {
                    prepared = false
                    updateSessionState()
                    notifyState()
                }

                Player.STATE_ENDED -> {
                    prepared = false
                    playing = false
                    notifyState()
                }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            playing = isPlaying
            if (isPlaying) {
                prepared = true
                startTicker()
            }
            updateSessionState()
            ensureForeground()
            notifyState()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (mediaItem == null) return
            val newIndex = mediaItem.mediaId.toLongOrNull()?.let { id ->
                queue.indexOfFirst { it.id == id }
            } ?: -1
            if (newIndex >= 0) index = newIndex

            val song = currentSong ?: return
            durationMs = song.durationMs
            artBitmap = AlbumArtLoader.peek(song, 512)
            prepared = player?.playbackState == Player.STATE_READY
            startArtworkLoad(song)
            updateMetadata()
            updateSessionState()
            ensureForeground()
            notifyState()
        }

        override fun onPlayerError(error: PlaybackException) {
            android.util.Log.e("BassPlayer", "player error ${error.errorCodeName}", error)
            prepared = false
            playing = false
            notifyState()
            mainHandler.post {
                listener?.onError("Nem sikerült lejátszani ezt a számot.")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()

        val mediaSession = MediaSessionCompat(this, "BassPlayer")
        mediaSession.setFlags(
            MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
        )
        mediaSession.setSessionActivity(openAppIntent())
        mediaSession.setCallback(object : MediaSessionCompat.Callback() {
            override fun onPlay() = startPlayback()

            override fun onPause() = pausePlayback(keepWantsPlay = false)

            override fun onSkipToNext() = skip(1)

            override fun onSkipToPrevious() = skip(-1)

            override fun onSeekTo(pos: Long) = seekTo(pos)

            override fun onStop() = stopAndCleanup()
        })
        mediaSession.isActive = true
        session = mediaSession
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> toggle()
            ACTION_NEXT -> skip(1)
            ACTION_PREV -> skip(-1)
            ACTION_STOP -> stopAndCleanup()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        releasePlayer()
        session?.release()
        session = null
        abandonFocus()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!playing) stopAndCleanup()
        super.onTaskRemoved(rootIntent)
    }

    fun playQueue(items: List<Song>, startIndex: Int) {
        if (items.isEmpty()) return
        if (!foregroundStarted) {
            try {
                startService(Intent(this, PlaybackService::class.java))
            } catch (_: Exception) {
            }
        }
        queue = items.toList()
        index = startIndex.coerceIn(items.indices)
        prepareQueue()
    }

    fun toggle() {
        if (player == null) {
            if (queue.isNotEmpty()) {
                if (index !in queue.indices) index = 0
                prepareQueue()
            }
            return
        }
        if (!prepared) {
            wantsPlay = !wantsPlay
            if (wantsPlay) startPlayback() else pausePlayback(keepWantsPlay = false)
            return
        }
        if (playing) {
            pausePlayback(keepWantsPlay = false)
        } else {
            startPlayback()
        }
    }

    fun seekTo(positionMs: Long) {
        val current = player ?: return
        try {
            current.seekTo(positionMs.coerceAtLeast(0L))
            updateSessionState(positionMs)
            listener?.onProgress(positionMs, durationMs)
        } catch (_: Exception) {
        }
    }

    fun skip(delta: Int) {
        if (queue.isEmpty()) return
        val current = player
        if (current == null) {
            index = ((index + delta) % queue.size + queue.size) % queue.size
            prepareQueue()
            return
        }

        val wasEnded = current.playbackState == Player.STATE_ENDED
        val before = current.currentMediaItemIndex
        val wasRepeatOne = current.repeatMode == Player.REPEAT_MODE_ONE
        try {
            // Kézi következő/előző: repeat one mellett is lépjen át a
            // szomszédos számra, ne az aktuálisat kezdje újra.
            if (wasRepeatOne) current.repeatMode = Player.REPEAT_MODE_ALL
            if (delta > 0) {
                current.seekToNextMediaItem()
            } else {
                current.seekToPreviousMediaItem()
            }
        } finally {
            if (wasRepeatOne) current.repeatMode = Player.REPEAT_MODE_ONE
        }

        if (wasEnded && current.currentMediaItemIndex != before) {
            try {
                current.prepare()
                if (wantsPlay) current.play()
            } catch (_: Exception) {
            }
        }
    }

    fun setShuffle(enabled: Boolean) {
        shuffleEnabled = enabled
        val current = player
        if (current != null) {
            if (enabled) {
                // Minden bekapcsoláskor új keverés, ne a régi sorrend.
                current.setShuffleOrder(
                    ShuffleOrder.DefaultShuffleOrder(
                        current.mediaItemCount,
                        System.nanoTime()
                    )
                )
            }
            current.shuffleModeEnabled = enabled
        }
        notifyState()
    }

    fun cycleRepeat() {
        repeatMode = when (repeatMode) {
            REPEAT_OFF -> REPEAT_ALL
            REPEAT_ALL -> REPEAT_ONE
            else -> REPEAT_OFF
        }
        applyPlaybackModes()
        notifyState()
    }

    private fun applyPlaybackModes() {
        applyPlaybackModesFor(player)
    }

    private fun applyPlaybackModesFor(exo: ExoPlayer?) {
        if (exo == null) return
        exo.shuffleModeEnabled = shuffleEnabled
        exo.repeatMode = when (repeatMode) {
            REPEAT_ALL -> Player.REPEAT_MODE_ALL
            REPEAT_ONE -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun snapshot(): PlaybackSnapshot {
        val current = player
        // Nem szabad a prepared/STATE_BUFFERING állapothoz kötni: seek után
        // az ExoPlayer rövid ideig bufferel, és a 0-val visszaadott pozíció
        // miatt a slider egy pillanatra 0:00-ra ugrana.
        val position = try {
            current?.currentPosition?.coerceAtLeast(0L) ?: 0L
        } catch (_: Exception) {
            0L
        }
        val sessionId = try {
            current?.audioSessionId ?: 0
        } catch (_: Exception) {
            0
        }
        return PlaybackSnapshot(
            song = currentSong,
            index = index,
            queueSize = queue.size,
            isPlaying = playing,
            isPrepared = prepared,
            positionMs = position,
            durationMs = durationMs,
            audioSessionId = sessionId,
            shuffle = current?.shuffleModeEnabled ?: shuffleEnabled,
            repeatMode = repeatMode
        )
    }

    private fun prepareQueue() {
        val song = currentSong ?: return

        releasePlayer()
        prepared = false
        playing = false
        wantsPlay = true
        durationMs = song.durationMs
        artBitmap = AlbumArtLoader.peek(song, 512)

        ensureForeground()
        updateMetadata()
        notifyState()
        startArtworkLoad(song)

        try {
            // A dekódolt PCM-et a hangerő/AudioTrack ELŐTT vesszük ki, így a
            // mérés folyamatos és hangerőfüggetlen (mint a HTML analyser a
            // gain előtt). A tap nem módosítja a hangot, csak továbbadja.
            val tapSink = MonoTapSink { AudioTap.sink }
            val renderersFactory = object : DefaultRenderersFactory(this) {
                override fun buildAudioSink(
                    context: Context,
                    enableFloatOutput: Boolean,
                    enableAudioTrackPlaybackParams: Boolean
                ): AudioSink = DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessorChain(
                        DefaultAudioSink.DefaultAudioProcessorChain(TeeAudioProcessor(tapSink))
                    )
                    .build()
            }
            val exo = ExoPlayer.Builder(this, renderersFactory).build()
            player = exo
            exo.setAudioAttributes(
                ExoAudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                false
            )
            exo.setHandleAudioBecomingNoisy(true)
            exo.setWakeMode(C.WAKE_MODE_LOCAL)
            exo.addListener(playerListener)
            applyPlaybackModesFor(exo)

            val mediaItems = queue.map { item ->
                MediaItem.Builder()
                    .setUri(item.uri)
                    .setMediaId(item.id.toString())
                    .build()
            }
            exo.setMediaItems(mediaItems, index, 0L)
            if (shuffleEnabled) {
                // Új lejátszási sor = új keverés.
                exo.setShuffleOrder(
                    ShuffleOrder.DefaultShuffleOrder(mediaItems.size, System.nanoTime())
                )
            }
            exo.prepare()

            if (wantsPlay && requestFocus()) {
                exo.play()
            }
        } catch (e: Exception) {
            releasePlayer()
            mainHandler.post {
                listener?.onError("Nem sikerült megnyitni a zenét.")
            }
            stopAndCleanup()
        }
    }

    private fun startPlayback() {
        val current = player ?: return
        if (!prepared) {
            wantsPlay = true
            if (current.playbackState == Player.STATE_ENDED) {
                current.seekTo(current.currentMediaItemIndex, 0L)
                current.prepare()
            }
            current.play()
            return
        }
        if (!requestFocus()) return
        wantsPlay = true
        current.play()
        playing = current.isPlaying
        updateSessionState()
        ensureForeground()
        notifyState()
        startTicker()
    }

    private fun pausePlayback(keepWantsPlay: Boolean) {
        try {
            player?.pause()
        } catch (_: Exception) {
        }
        playing = false
        if (!keepWantsPlay) wantsPlay = false
        updateSessionState()
        ensureForeground()
        notifyState()
    }

    private fun startTicker() {
        mainHandler.removeCallbacks(ticker)
        mainHandler.post(ticker)
    }

    private fun stopAndCleanup() {
        mainHandler.removeCallbacksAndMessages(null)
        releasePlayer()
        playing = false
        prepared = false
        wantsPlay = false
        durationMs = 0L
        queue = emptyList()
        index = -1
        artBitmap = null
        session?.isActive = false
        notifyState()
        if (foregroundStarted) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
        }
        stopSelf()
    }

    private fun releasePlayer() {
        mainHandler.removeCallbacks(ticker)
        val current = player
        player = null
        if (current != null) {
            releasing = true
            try {
                current.removeListener(playerListener)
                current.stop()
                current.release()
            } catch (_: Exception) {
            }
            releasing = false
        }
    }

    private fun requestFocus(): Boolean {
        var request = focusRequest
        if (request == null) {
            request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setOnAudioFocusChangeListener(this)
                .setWillPauseWhenDucked(false)
                .build()
            focusRequest = request
        }
        return audioManager.requestAudioFocus(request) ==
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    override fun onAudioFocusChange(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                wantsPlay = false
                resumeOnFocusGain = false
                pausePlayback(keepWantsPlay = false)
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                resumeOnFocusGain = playing
                pausePlayback(keepWantsPlay = true)
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                try {
                    player?.volume = 0.2f
                } catch (_: Exception) {
                }
            }

            AudioManager.AUDIOFOCUS_GAIN -> {
                try {
                    player?.volume = 1f
                } catch (_: Exception) {
                }
                if (resumeOnFocusGain && prepared && !playing && wantsPlay) {
                    resumeOnFocusGain = false
                    startPlayback()
                }
            }
        }
    }

    private fun updateSessionState(position: Long = currentPosition()) {
        val mediaSession = session ?: return
        val current = player
        val buffering = current == null || current.playbackState == Player.STATE_BUFFERING
        val state = when {
            buffering && wantsPlay -> PlaybackStateCompat.STATE_BUFFERING
            playing -> PlaybackStateCompat.STATE_PLAYING
            else -> PlaybackStateCompat.STATE_PAUSED
        }
        val builder = PlaybackStateCompat.Builder()
            .setActions(SESSION_ACTIONS)
            .setState(state, position, if (playing) 1f else 0f)
        mediaSession.setPlaybackState(builder.build())
    }

    private fun currentPosition(): Long {
        val current = player ?: return 0L
        return try {
            current.currentPosition.coerceAtLeast(0L)
        } catch (_: Exception) {
            0L
        }
    }

    private fun startArtworkLoad(song: Song) {
        AlbumArtLoader.load(this, song, 512) { bitmap ->
            if (currentSong?.id != song.id) return@load
            artBitmap = bitmap
            updateMetadata()
            updateNotification()
        }
    }

    private fun updateMetadata() {
        val mediaSession = session ?: return
        val song = currentSong ?: return
        val builder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, song.title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, song.artist)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, song.album)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, durationMs)
        artBitmap?.let { builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it) }
        mediaSession.setMetadata(builder.build())
    }

    private fun notifyState() {
        if (releasing) return
        val state = snapshot()
        mainHandler.post { listener?.onStateChanged(state) }
    }

    private fun ensureForeground() {
        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            foregroundStarted = true
        } catch (_: Exception) {
        }
    }

    private fun updateNotification() {
        if (!foregroundStarted) return
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(NOTIFICATION_ID, buildNotification())
        } catch (_: Exception) {
        }
    }

    private fun buildNotification(): Notification {
        val song = currentSong
        val playPauseIcon = if (playing) R.drawable.ic_pause else R.drawable.ic_play

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(song?.title ?: "Bass Player")
            .setContentText(song?.artist ?: "")
            .setContentIntent(openAppIntent())
            .setDeleteIntent(serviceIntent(ACTION_STOP, 4))
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setOngoing(playing)
            .setColor(0xFF1DB954.toInt())
            .addAction(R.drawable.ic_prev, if (isEnglish) "Previous" else "Előző", serviceIntent(ACTION_PREV, 1))
            .addAction(
                playPauseIcon,
                if (isEnglish) { if (playing) "Pause" else "Play" } else { if (playing) "Szünet" else "Lejátszás" },
                serviceIntent(ACTION_TOGGLE, 2)
            )
            .addAction(R.drawable.ic_next, if (isEnglish) "Next" else "Következő", serviceIntent(ACTION_NEXT, 3))

        artBitmap?.let { builder.setLargeIcon(it) }

        session?.let {
            builder.setStyle(
                MediaStyle()
                    .setMediaSession(it.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
        }

        return builder.build()
    }

    private fun serviceIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, PlaybackService::class.java).setAction(action)
        return PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            if (isEnglish) "Playback" else "Lejátszás",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = if (isEnglish) "Bass Player playback controls" else "Bass Player lejátszó vezérlői"
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
    }
}

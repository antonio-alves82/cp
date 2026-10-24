package com.classicplayer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.widget.Toast
import java.util.concurrent.CopyOnWriteArrayList

class PlayerService : Service() {

    interface Listener {
        fun onPlayerChanged()
    }

    inner class LocalBinder : Binder() {
        val service: PlayerService get() = this@PlayerService
    }

    companion object {
        const val ACTION_PLAY_PAUSE = "com.classicplayer.PLAY_PAUSE"
        const val ACTION_NEXT = "com.classicplayer.NEXT"
        const val ACTION_PREV = "com.classicplayer.PREV"
        const val ACTION_STOP = "com.classicplayer.STOP"
        const val REPEAT_OFF = 0
        const val REPEAT_ALL = 1
        const val REPEAT_ONE = 2
        private const val CHANNEL_ID = "playback"
        private const val NOTIF_ID = 1
    }

    private val binder = LocalBinder()
    private val handler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<Listener>()

    private var queue: List<Track> = emptyList()
    private var order: IntArray = IntArray(0)
    private var pos = -1
    private var player: MediaPlayer? = null
    private var prepared = false
    private var wantPlay = false
    private var errorCount = 0
    private var resumeOnFocus = false
    private var foreground = false

    var shuffle = false
        private set
    var repeatMode = REPEAT_OFF
        private set
    var artwork: Bitmap? = null
        private set

    private lateinit var session: MediaSession
    private lateinit var audioManager: AudioManager
    private lateinit var notificationManager: NotificationManager
    private var focusRequest: AudioFocusRequest? = null

    private val audioAttrs: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    // ------------------------------------------------------------------ estado público

    val currentTrack: Track?
        get() = if (pos in order.indices) queue[order[pos]] else null

    val queueSize: Int get() = queue.size

    val queuePosition: Int get() = if (pos in order.indices) pos + 1 else 0

    val playing: Boolean
        get() {
            val p = player ?: return false
            if (!prepared) return false
            return try {
                p.isPlaying
            } catch (e: IllegalStateException) {
                false
            }
        }

    val positionMs: Int
        get() {
            val p = player ?: return 0
            if (!prepared) return 0
            return try {
                p.currentPosition
            } catch (e: IllegalStateException) {
                0
            }
        }

    val durationMs: Int
        get() {
            val p = player
            if (prepared && p != null) {
                try {
                    val d = p.duration
                    if (d > 0) return d
                } catch (e: IllegalStateException) {
                    // usa a duração dos metadados
                }
            }
            return currentTrack?.durationMs ?: 0
        }

    fun addListener(l: Listener) {
        listeners.add(l)
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    private fun notifyChanged() {
        for (l in listeners) l.onPlayerChanged()
    }

    // ------------------------------------------------------------------ ciclo de vida

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Reprodução", NotificationManager.IMPORTANCE_LOW)
        )

        session = MediaSession(this, "ClassicPlayer")
        session.setCallback(object : MediaSession.Callback() {
            override fun onPlay() {
                resume()
            }

            override fun onPause() {
                pause()
            }

            override fun onSkipToNext() {
                next()
            }

            override fun onSkipToPrevious() {
                prev()
            }

            override fun onSeekTo(pos: Long) {
                seekTo(pos.toInt())
            }

            override fun onStop() {
                stopAll()
            }
        })
        session.isActive = true

        registerReceiver(noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> togglePlayPause()
            ACTION_NEXT -> next()
            ACTION_PREV -> prev()
            ACTION_STOP -> stopAll()
        }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!wantPlay) stopAll()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        releasePlayer()
        abandonFocus()
        try {
            unregisterReceiver(noisyReceiver)
        } catch (e: Exception) {
            // já removido
        }
        session.release()
        super.onDestroy()
    }

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pause()
        }
    }

    // ------------------------------------------------------------------ fila

    private fun rebuildOrder(start: Int) {
        val n = queue.size
        if (n == 0) {
            order = IntArray(0)
            pos = -1
            return
        }
        val s = start.coerceIn(0, n - 1)
        if (shuffle) {
            val rest = (0 until n).filter { it != s }.shuffled()
            order = IntArray(n)
            order[0] = s
            for (i in rest.indices) order[i + 1] = rest[i]
            pos = 0
        } else {
            order = IntArray(n) { it }
            pos = s
        }
    }

    fun playQueue(tracks: List<Track>, start: Int) {
        if (tracks.isEmpty()) return
        queue = tracks
        rebuildOrder(start)
        errorCount = 0
        startCurrent()
    }

    fun setShuffle(on: Boolean) {
        if (shuffle == on) return
        shuffle = on
        if (queue.isNotEmpty()) {
            val cur = if (pos in order.indices) order[pos] else 0
            rebuildOrder(cur)
        }
        notifyChanged()
    }

    fun setRepeat(mode: Int) {
        repeatMode = mode
        notifyChanged()
    }

    // ------------------------------------------------------------------ reprodução

    private fun ensureStarted() {
        try {
            startService(Intent(this, PlayerService::class.java))
        } catch (e: Exception) {
            // já está rodando
        }
    }

    private fun startCurrent() {
        val t = currentTrack ?: return
        ensureStarted()
        releasePlayer()
        requestFocus()
        wantPlay = true
        artwork = null

        val mp = MediaPlayer()
        player = mp
        prepared = false
        try {
            mp.setWakeMode(applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
            mp.setAudioAttributes(audioAttrs)
            mp.setDataSource(applicationContext, Uri.parse(t.uri))
            mp.setOnPreparedListener { p ->
                prepared = true
                errorCount = 0
                if (wantPlay) p.start()
                afterStateChange()
            }
            mp.setOnCompletionListener { onCompleted() }
            mp.setOnErrorListener { _, _, _ ->
                handler.post { handleError() }
                true
            }
            mp.prepareAsync()
        } catch (e: Exception) {
            handler.post { handleError() }
        }
        loadArtwork(t)
        afterStateChange()
    }

    private fun handleError() {
        errorCount++
        if (errorCount > queue.size.coerceAtMost(10)) {
            releasePlayer()
            wantPlay = false
            Toast.makeText(this, "Não foi possível reproduzir os arquivos", Toast.LENGTH_SHORT).show()
            afterStateChange()
            return
        }
        advance(1, true)
    }

    private fun onCompleted() {
        if (repeatMode == REPEAT_ONE) {
            player?.seekTo(0)
            player?.start()
            return
        }
        advance(1, true)
    }

    private fun advance(delta: Int, auto: Boolean) {
        val n = order.size
        if (n == 0) return
        var np = pos + delta
        if (np >= n) {
            if (auto && repeatMode != REPEAT_ALL) {
                // fim da fila: volta para a primeira faixa, pausado
                pos = 0
                releasePlayer()
                wantPlay = false
                artwork = null
                afterStateChange()
                return
            }
            np = 0
        } else if (np < 0) {
            np = n - 1
        }
        pos = np
        startCurrent()
    }

    fun next() {
        advance(1, false)
    }

    fun prev() {
        if (positionMs > 3000) seekTo(0) else advance(-1, false)
    }

    fun togglePlayPause() {
        if (currentTrack == null) return
        if (player != null && wantPlay) pause() else resume()
    }

    fun pause() {
        val p = player
        if (p != null && prepared) {
            try {
                if (p.isPlaying) p.pause()
            } catch (e: IllegalStateException) {
                // ignora
            }
        }
        wantPlay = false
        resumeOnFocus = false
        afterStateChange()
    }

    fun resume() {
        if (currentTrack == null) return
        val p = player
        if (p == null) {
            startCurrent()
            return
        }
        wantPlay = true
        if (!prepared) {
            afterStateChange()
            return
        }
        requestFocus()
        try {
            p.start()
        } catch (e: IllegalStateException) {
            startCurrent()
            return
        }
        afterStateChange()
    }

    fun seekTo(ms: Int) {
        val p = player ?: return
        if (!prepared) return
        try {
            val d = durationMs
            p.seekTo(if (d > 0) ms.coerceIn(0, d) else ms.coerceAtLeast(0))
        } catch (e: IllegalStateException) {
            // ignora
        }
        updateSession()
        notifyChanged()
    }

    fun stopAll() {
        releasePlayer()
        wantPlay = false
        abandonFocus()
        queue = emptyList()
        order = IntArray(0)
        pos = -1
        artwork = null
        updateSession()
        stopForeground(Service.STOP_FOREGROUND_REMOVE)
        foreground = false
        notificationManager.cancel(NOTIF_ID)
        notifyChanged()
        stopSelf()
    }

    private fun releasePlayer() {
        val p = player ?: return
        player = null
        prepared = false
        try {
            p.setOnPreparedListener(null)
            p.setOnCompletionListener(null)
            p.setOnErrorListener(null)
            p.reset()
        } catch (e: Exception) {
            // ignora
        }
        try {
            p.release()
        } catch (e: Exception) {
            // ignora
        }
    }

    private fun afterStateChange() {
        updateSession()
        updateNotification()
        notifyChanged()
    }

    // ------------------------------------------------------------------ foco de áudio

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                pause()
                abandonFocus()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (playing) {
                    pause()
                    resumeOnFocus = true
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> player?.setVolume(0.25f, 0.25f)
            AudioManager.AUDIOFOCUS_GAIN -> {
                player?.setVolume(1f, 1f)
                if (resumeOnFocus) {
                    resumeOnFocus = false
                    resume()
                }
            }
        }
    }

    private fun requestFocus(): Boolean {
        val req = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(audioAttrs)
            .setOnAudioFocusChangeListener(focusListener)
            .build()
            .also { focusRequest = it }
        return audioManager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
    }

    // ------------------------------------------------------------------ capa do álbum

    private fun loadArtwork(t: Track) {
        Thread {
            var bmp: Bitmap? = null
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(applicationContext, Uri.parse(t.uri))
                val data = r.embeddedPicture
                if (data != null) {
                    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(data, 0, data.size, opts)
                    var sample = 1
                    while (opts.outWidth / sample > 800) sample *= 2
                    val o2 = BitmapFactory.Options().apply { inSampleSize = sample }
                    bmp = BitmapFactory.decodeByteArray(data, 0, data.size, o2)
                }
            } catch (e: Exception) {
                // sem capa
            } finally {
                try {
                    r.release()
                } catch (e: Exception) {
                    // ignora
                }
            }
            handler.post {
                if (currentTrack?.uri == t.uri) {
                    artwork = bmp
                    updateSession()
                    updateNotification()
                    notifyChanged()
                }
            }
        }.start()
    }

    // ------------------------------------------------------------------ sessão de mídia

    private fun updateSession() {
        val t = currentTrack
        val st = when {
            t == null -> PlaybackState.STATE_STOPPED
            playing -> PlaybackState.STATE_PLAYING
            wantPlay && !prepared -> PlaybackState.STATE_BUFFERING
            else -> PlaybackState.STATE_PAUSED
        }
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or
                        PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SEEK_TO or
                        PlaybackState.ACTION_STOP
                )
                .setState(st, positionMs.toLong(), if (st == PlaybackState.STATE_PLAYING) 1f else 0f)
                .build()
        )
        if (t != null) {
            session.setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, t.title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, t.artist)
                    .putString(MediaMetadata.METADATA_KEY_ALBUM, t.album)
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, durationMs.toLong())
                    .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, artwork)
                    .build()
            )
        }
    }

    // ------------------------------------------------------------------ notificação

    private fun action(icon: Int, title: String, action: String): Notification.Action {
        val pi = PendingIntent.getService(
            this,
            action.hashCode(),
            Intent(this, PlayerService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Action.Builder(Icon.createWithResource(this, icon), title, pi).build()
    }

    private fun buildNotification(): Notification {
        val t = currentTrack
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_music)
            .setContentTitle(t?.title ?: "Classic Player")
            .setContentText(t?.artist ?: "")
            .setContentIntent(open)
            .setOngoing(wantPlay)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(action(R.drawable.ic_prev, "Anterior", ACTION_PREV))
            .addAction(
                if (wantPlay) action(R.drawable.ic_pause, "Pausar", ACTION_PLAY_PAUSE)
                else action(R.drawable.ic_play, "Tocar", ACTION_PLAY_PAUSE)
            )
            .addAction(action(R.drawable.ic_next, "Próxima", ACTION_NEXT))
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
        artwork?.let { b.setLargeIcon(it) }
        return b.build()
    }

    private fun updateNotification() {
        if (currentTrack == null) return
        val n = buildNotification()
        if (wantPlay) {
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                } else {
                    startForeground(NOTIF_ID, n)
                }
                foreground = true
            } catch (e: Exception) {
                notificationManager.notify(NOTIF_ID, n)
            }
        } else {
            if (foreground) {
                stopForeground(Service.STOP_FOREGROUND_DETACH)
                foreground = false
            }
            notificationManager.notify(NOTIF_ID, n)
        }
    }
}

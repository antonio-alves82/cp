package com.classicplayer

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.DocumentsContract
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Toast
import kotlin.math.min
import kotlin.random.Random

/** Uma "tela" do menu estilo iPod. */
class Screen(
    val id: String,
    val title: String,
    var items: List<Item>,
    val rowDp: Int = 48,
    val nowPlaying: Boolean = false,
    val refresh: (() -> List<Item>)? = null,
    val onSelect: (Int) -> Unit = {}
) {
    var selected = 0
    var firstPos = 0
    var topOffset = 0
}

class MainActivity : Activity() {

    companion object {
        private const val KEY_FOLDER = "folder"
        private const val KEY_ASKED = "asked_folder"
        private const val KEY_ASKED_NOTIF = "asked_notif"
        private const val KEY_SHUFFLE = "shuffle"
        private const val KEY_REPEAT = "repeat"
        private const val REQ_FOLDER = 100
        private const val REQ_NOTIF = 101
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var audioManager: AudioManager
    private lateinit var titleBar: TitleBarView
    private lateinit var listView: ListView
    private lateinit var adapter: ItemAdapter
    private lateinit var nowPlayingView: NowPlayingView
    private lateinit var wheel: ClickWheelView

    private val stack = ArrayList<Screen>()
    private val handler = Handler(Looper.getMainLooper())
    private var service: PlayerService? = null
    private var scrubMode = false
    private var scanning = false
    private var tickCount = 0

    private val screen: Screen get() = stack.last()

    // ------------------------------------------------------------------ ciclo de vida

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("classic", MODE_PRIVATE)
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        Library.load(this)

        buildUi()
        push(mainScreen())
        updateBattery()

        startService(Intent(this, PlayerService::class.java))
        bindService(Intent(this, PlayerService::class.java), connection, BIND_AUTO_CREATE)

        if (prefs.getString(KEY_FOLDER, null) == null && !prefs.getBoolean(KEY_ASKED, false)) {
            prefs.edit().putBoolean(KEY_ASKED, true).apply()
            handler.postDelayed({ pickFolder() }, 400)
        }
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    override fun onDestroy() {
        service?.removeListener(playerListener)
        try {
            unbindService(connection)
        } catch (e: Exception) {
            // já desvinculado
        }
        super.onDestroy()
    }

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onBackPressed() {
        if (stack.size > 1) pop() else super.onBackPressed()
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val s = (binder as PlayerService.LocalBinder).service
            service = s
            s.addListener(playerListener)
            s.setShuffle(prefs.getBoolean(KEY_SHUFFLE, false))
            s.setRepeat(prefs.getInt(KEY_REPEAT, PlayerService.REPEAT_OFF))
            refreshPlayback()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    private val playerListener = object : PlayerService.Listener {
        override fun onPlayerChanged() {
            refreshPlayback()
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            tickCount++
            if (tickCount % 20 == 0) updateBattery()
            refreshPlayback()
            handler.postDelayed(this, 400)
        }
    }

    private val hideVolume = Runnable { nowPlayingView.showVolume(0f, false) }

    // ------------------------------------------------------------------ UI

    private fun buildUi() {
        val match = ViewGroup.LayoutParams.MATCH_PARENT

        val root = FrameLayout(this)
        root.setBackgroundColor(Ui.BG)
        root.fitsSystemWindows = true

        val body = LinearLayout(this)
        body.orientation = LinearLayout.VERTICAL
        body.gravity = Gravity.CENTER_HORIZONTAL
        body.setPadding(dpi(14), dpi(8), dpi(14), dpi(14))

        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        val bg = GradientDrawable()
        bg.setColor(Color.WHITE)
        bg.cornerRadius = dpf(14)
        bg.setStroke(dpi(1), Ui.SEPARATOR)
        card.background = bg
        card.outlineProvider = ViewOutlineProvider.BACKGROUND
        card.clipToOutline = true

        titleBar = TitleBarView(this)
        card.addView(titleBar, LinearLayout.LayoutParams(match, ViewGroup.LayoutParams.WRAP_CONTENT))

        val content = FrameLayout(this)

        adapter = ItemAdapter(this)
        listView = ListView(this)
        listView.setBackgroundColor(Color.WHITE)
        listView.divider = null
        listView.dividerHeight = 0
        listView.selector = ColorDrawable(Color.TRANSPARENT)
        listView.cacheColorHint = Color.TRANSPARENT
        listView.overScrollMode = View.OVER_SCROLL_NEVER
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, position, _ -> select(position) }
        content.addView(listView, FrameLayout.LayoutParams(match, match))

        nowPlayingView = NowPlayingView(this)
        nowPlayingView.visibility = View.GONE
        nowPlayingView.setOnClickListener { service?.togglePlayPause() }
        content.addView(nowPlayingView, FrameLayout.LayoutParams(match, match))

        card.addView(content, LinearLayout.LayoutParams(match, 0, 1f))
        body.addView(card, LinearLayout.LayoutParams(match, 0, 1f))

        val dm = resources.displayMetrics
        val size = min(dm.widthPixels * 0.78f, dm.heightPixels * 0.38f).toInt()
        wheel = ClickWheelView(this)
        val wlp = LinearLayout.LayoutParams(size, size)
        wlp.topMargin = dpi(18)
        wlp.gravity = Gravity.CENTER_HORIZONTAL
        body.addView(wheel, wlp)

        wheel.listener = object : ClickWheelView.Listener {
            override fun onRotate(steps: Int) = handleRotate(steps)
            override fun onCenter() = handleCenter()
            override fun onMenu() = handleMenu()
            override fun onNext() {
                service?.next()
            }

            override fun onPrev() {
                service?.prev()
            }

            override fun onPlayPause() {
                service?.togglePlayPause()
            }
        }

        root.addView(body, FrameLayout.LayoutParams(match, match))
        setContentView(root)
    }

    // ------------------------------------------------------------------ navegação

    private fun saveScroll() {
        val s = stack.last()
        if (s.nowPlaying) return
        s.firstPos = listView.firstVisiblePosition
        s.topOffset = listView.getChildAt(0)?.top ?: 0
    }

    private fun push(s: Screen) {
        if (stack.isNotEmpty()) saveScroll()
        stack.add(s)
        show(s)
    }

    private fun pop() {
        if (stack.size <= 1) return
        stack.removeAt(stack.size - 1)
        show(stack.last())
    }

    private fun show(s: Screen) {
        scrubMode = false
        nowPlayingView.scrub = false
        if (s.nowPlaying) {
            listView.visibility = View.GONE
            nowPlayingView.visibility = View.VISIBLE
            refreshPlayback()
        } else {
            nowPlayingView.visibility = View.GONE
            listView.visibility = View.VISIBLE
            val r = s.refresh
            if (r != null) s.items = r()
            if (s.selected >= s.items.size) s.selected = (s.items.size - 1).coerceAtLeast(0)
            adapter.rowHeight = dpi(s.rowDp)
            adapter.items = s.items
            adapter.selected = s.selected
            adapter.currentUri = service?.currentTrack?.uri
            adapter.notifyDataSetChanged()
            listView.setSelectionFromTop(s.firstPos, s.topOffset)
            titleBar.title = s.title
        }
    }

    private fun refreshCurrentItems() {
        val s = screen
        if (s.nowPlaying) return
        val r = s.refresh ?: return
        s.items = r()
        adapter.items = s.items
        adapter.notifyDataSetChanged()
    }

    private fun select(position: Int) {
        val s = screen
        if (s.nowPlaying || position !in s.items.indices) return
        s.selected = position
        adapter.selected = position
        adapter.notifyDataSetChanged()
        s.onSelect(position)
    }

    private fun moveSelection(delta: Int) {
        val s = screen
        val n = s.items.size
        if (s.nowPlaying || n == 0) return
        val ns = (s.selected + delta).coerceIn(0, n - 1)
        if (ns == s.selected) return
        s.selected = ns
        adapter.selected = ns
        adapter.notifyDataSetChanged()
        ensureVisible(ns)
    }

    private fun ensureVisible(position: Int) {
        val rowH = adapter.rowHeight
        if (rowH <= 0 || adapter.count * rowH <= listView.height) return
        val first = listView.firstVisiblePosition
        val last = listView.lastVisiblePosition
        if (position <= first) {
            listView.setSelectionFromTop(position, 0)
        } else if (position >= last) {
            listView.setSelectionFromTop(position, listView.height - rowH)
        }
    }

    private fun openNowPlaying() {
        if (stack.last().nowPlaying) return
        push(Screen("np", "Reproduzindo", emptyList(), nowPlaying = true))
    }

    // ------------------------------------------------------------------ click wheel

    private fun handleRotate(steps: Int) {
        if (screen.nowPlaying) {
            if (scrubMode) {
                val sv = service ?: return
                sv.seekTo(sv.positionMs + steps * 4000)
                refreshPlayback()
            } else {
                changeVolume(steps)
            }
        } else {
            moveSelection(steps)
        }
    }

    private fun handleCenter() {
        if (screen.nowPlaying) {
            scrubMode = !scrubMode
            nowPlayingView.scrub = scrubMode
        } else {
            select(screen.selected)
        }
    }

    private fun handleMenu() {
        if (stack.size > 1) pop()
    }

    private fun changeVolume(steps: Int) {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val nv = (cur + steps).coerceIn(0, max)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, nv, 0)
        nowPlayingView.showVolume(if (max > 0) nv.toFloat() / max else 0f, true)
        handler.removeCallbacks(hideVolume)
        handler.postDelayed(hideVolume, 1500)
    }

    // ------------------------------------------------------------------ telas

    private fun mainScreen(): Screen {
        return Screen(
            id = "main",
            title = "Classic",
            items = emptyList(),
            rowDp = 50,
            refresh = { mainItems() },
            onSelect = { i ->
                when (screen.items[i].id) {
                    "pick" -> pickFolder()
                    "now" -> openNowPlaying()
                    "songs" -> push(songsScreen())
                    "artists" -> push(artistsScreen())
                    "albums" -> push(albumsScreen())
                    "shuffle" -> shuffleAll()
                    "settings" -> push(settingsScreen())
                }
            }
        )
    }

    private fun mainItems(): List<Item> {
        val l = ArrayList<Item>()
        if (service?.currentTrack != null) l.add(Item("now", "Reproduzindo"))
        if (prefs.getString(KEY_FOLDER, null) == null) l.add(Item("pick", "Escolher pasta de músicas"))
        l.add(Item("songs", "Músicas"))
        l.add(Item("artists", "Artistas"))
        l.add(Item("albums", "Álbuns"))
        l.add(Item("shuffle", "Aleatório", chevron = false))
        l.add(Item("settings", "Configurações"))
        return l
    }

    private fun emptyItems(): List<Item> {
        val msg = if (prefs.getString(KEY_FOLDER, null) == null)
            "Escolha uma pasta em Configurações"
        else
            "Nenhuma música encontrada"
        return listOf(Item("empty", msg, chevron = false))
    }

    private fun trackItems(list: List<Track>): List<Item> =
        list.map { Item("t", it.title, subtitle = "${it.artist} — ${it.album}", chevron = false, key = it.uri) }

    private fun songsScreen(): Screen {
        return Screen(
            id = "songs",
            title = "Músicas",
            items = emptyList(),
            rowDp = 56,
            refresh = { if (Library.tracks.isEmpty()) emptyItems() else trackItems(Library.tracks) },
            onSelect = { i ->
                if (Library.tracks.isNotEmpty()) playList(Library.tracks, i)
            }
        )
    }

    private fun albumTracksScreen(title: String, list: List<Track>): Screen {
        return Screen(
            id = "album",
            title = title,
            items = trackItems(list),
            rowDp = 56,
            onSelect = { i -> playList(list, i) }
        )
    }

    private fun albumsScreen(): Screen {
        return Screen(
            id = "albums",
            title = "Álbuns",
            items = emptyList(),
            rowDp = 56,
            refresh = {
                val albums = Library.albums()
                if (albums.isEmpty()) emptyItems()
                else albums.map { Item("a", it.name, subtitle = it.artist) }
            },
            onSelect = { i ->
                val albums = Library.albums()
                if (i in albums.indices) push(albumTracksScreen(albums[i].name, albums[i].tracks))
            }
        )
    }

    private fun artistsScreen(): Screen {
        return Screen(
            id = "artists",
            title = "Artistas",
            items = emptyList(),
            rowDp = 56,
            refresh = {
                val artists = Library.artists()
                if (artists.isEmpty()) emptyItems()
                else artists.map {
                    Item("ar", it.name, subtitle = "${it.albumCount} álbuns · ${it.trackCount} músicas")
                }
            },
            onSelect = { i ->
                val artists = Library.artists()
                if (i in artists.indices) push(artistAlbumsScreen(artists[i].name))
            }
        )
    }

    private fun artistAlbumsScreen(artist: String): Screen {
        val albums = Library.albums(artist)
        val allTracks = albums.flatMap { it.tracks }
        val items = ArrayList<Item>()
        items.add(Item("all", "Todas as músicas", subtitle = "${allTracks.size} músicas"))
        for (a in albums) items.add(Item("a", a.name, subtitle = "${a.tracks.size} músicas"))
        return Screen(
            id = "artist",
            title = artist,
            items = items,
            rowDp = 56,
            onSelect = { i ->
                if (i == 0) push(albumTracksScreen(artist, allTracks))
                else push(albumTracksScreen(albums[i - 1].name, albums[i - 1].tracks))
            }
        )
    }

    private fun settingsScreen(): Screen {
        return Screen(
            id = "settings",
            title = "Configurações",
            items = emptyList(),
            rowDp = 56,
            refresh = { settingsItems() },
            onSelect = { i ->
                when (screen.items[i].id) {
                    "folder" -> pickFolder()
                    "rescan" -> rescan()
                    "shuffle" -> toggleShuffle()
                    "repeat" -> cycleRepeat()
                }
                refreshCurrentItems()
            }
        )
    }

    private fun settingsItems(): List<Item> {
        val shuffleOn = prefs.getBoolean(KEY_SHUFFLE, false)
        val repeat = prefs.getInt(KEY_REPEAT, PlayerService.REPEAT_OFF)
        return listOf(
            Item("folder", "Pasta de músicas", subtitle = folderLabel() ?: "Toque para escolher"),
            Item(
                "rescan", "Atualizar biblioteca",
                subtitle = if (scanning) "Lendo arquivos…" else "${Library.tracks.size} músicas",
                chevron = false
            ),
            Item("shuffle", "Aleatório", value = if (shuffleOn) "Ligado" else "Desligado", chevron = false),
            Item(
                "repeat", "Repetir",
                value = when (repeat) {
                    PlayerService.REPEAT_ALL -> "Todas"
                    PlayerService.REPEAT_ONE -> "Uma"
                    else -> "Desligado"
                },
                chevron = false
            )
        )
    }

    private fun folderLabel(): String? {
        val s = prefs.getString(KEY_FOLDER, null) ?: return null
        return try {
            val id = Uri.decode(DocumentsContract.getTreeDocumentId(Uri.parse(s)))
            val path = id.substringAfter(':', "")
            if (path.isEmpty()) "Armazenamento interno" else path
        } catch (e: Exception) {
            null
        }
    }

    // ------------------------------------------------------------------ ações

    private fun playList(list: List<Track>, index: Int) {
        val s = service ?: return
        s.playQueue(list, index)
        ensureNotificationPermission()
        openNowPlaying()
    }

    private fun shuffleAll() {
        val all = Library.tracks
        val s = service ?: return
        if (all.isEmpty()) {
            toast("Nenhuma música na biblioteca")
            return
        }
        prefs.edit().putBoolean(KEY_SHUFFLE, true).apply()
        s.setShuffle(true)
        playList(all, Random.nextInt(all.size))
    }

    private fun toggleShuffle() {
        val on = !prefs.getBoolean(KEY_SHUFFLE, false)
        prefs.edit().putBoolean(KEY_SHUFFLE, on).apply()
        service?.setShuffle(on)
    }

    private fun cycleRepeat() {
        val next = (prefs.getInt(KEY_REPEAT, PlayerService.REPEAT_OFF) + 1) % 3
        prefs.edit().putInt(KEY_REPEAT, next).apply()
        service?.setRepeat(next)
    }

    @Suppress("DEPRECATION")
    private fun pickFolder() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        i.addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        )
        try {
            startActivityForResult(i, REQ_FOLDER)
        } catch (e: ActivityNotFoundException) {
            toast("Seu aparelho não tem seletor de pastas")
        }
    }

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_FOLDER || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Exception) {
            // algumas pastas não aceitam permissão persistente
        }
        val old = prefs.getString(KEY_FOLDER, null)
        if (old != null && old != uri.toString()) {
            try {
                contentResolver.releasePersistableUriPermission(
                    Uri.parse(old), Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: Exception) {
                // ignora
            }
        }
        prefs.edit().putString(KEY_FOLDER, uri.toString()).apply()
        rescan()
    }

    private fun rescan() {
        val folder = prefs.getString(KEY_FOLDER, null)
        if (folder == null) {
            pickFolder()
            return
        }
        if (scanning) return
        scanning = true
        titleBar.title = "Lendo arquivos…"
        Thread {
            var list: List<Track> = emptyList()
            try {
                list = Library.scan(this, Uri.parse(folder)) { n ->
                    if (n % 5 == 0) handler.post { if (scanning) titleBar.title = "Lendo $n…" }
                }
            } catch (e: Exception) {
                handler.post { toast("Erro ao ler a pasta") }
            }
            Library.setTracks(list)
            Library.save(this)
            handler.post {
                scanning = false
                toast("${list.size} músicas encontradas")
                resetAfterScan()
            }
        }.start()
    }

    /** Depois de reler a biblioteca, telas fundas ficam obsoletas: volta para a raiz (ou Configurações). */
    private fun resetAfterScan() {
        val wasSettings = stack.isNotEmpty() && stack.last().id == "settings"
        while (stack.size > 1) stack.removeAt(stack.size - 1)
        stack[0].selected = 0
        stack[0].firstPos = 0
        stack[0].topOffset = 0
        show(stack[0])
        if (wasSettings) push(settingsScreen())
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        if (prefs.getBoolean(KEY_ASKED_NOTIF, false)) return
        prefs.edit().putBoolean(KEY_ASKED_NOTIF, true).apply()
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
    }

    // ------------------------------------------------------------------ atualização de estado

    private fun refreshPlayback() {
        if (stack.isEmpty()) return
        val sv = service
        val track = sv?.currentTrack

        titleBar.playState = when {
            sv == null || track == null -> 0
            sv.playing -> 1
            else -> 2
        }

        val uri = track?.uri
        if (adapter.currentUri != uri) {
            adapter.currentUri = uri
            adapter.notifyDataSetChanged()
        }

        val s = stack.last()
        if (s.nowPlaying) {
            if (sv == null || track == null) {
                pop()
                return
            }
            nowPlayingView.update(
                track, sv.artwork, sv.positionMs, sv.durationMs, sv.shuffle, sv.repeatMode
            )
            titleBar.title = "${sv.queuePosition} de ${sv.queueSize}"
        } else if (s.id == "main") {
            val wanted = mainItems()
            if (wanted.size != s.items.size) {
                s.items = wanted
                adapter.items = wanted
                adapter.notifyDataSetChanged()
            }
        }
    }

    private fun updateBattery() {
        val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level >= 0 && scale > 0) titleBar.batteryPct = level * 100 / scale
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        titleBar.charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}

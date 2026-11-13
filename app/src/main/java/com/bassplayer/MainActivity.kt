package com.bassplayer

import android.Manifest
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale

class MainActivity : AppCompatActivity(), PlaybackService.Listener {

    private lateinit var songList: RecyclerView
    private lateinit var mainContent: View
    private lateinit var countText: TextView
    private lateinit var subtitleText: TextView
    private lateinit var searchInput: EditText
    private lateinit var filterBtn: ImageButton
    private lateinit var favoritesFilterBtn: ImageButton
    private lateinit var emptyState: View
    private lateinit var emptyText: TextView
    private lateinit var loading: ProgressBar

    private lateinit var playerPanel: View
    private lateinit var nowCover: ImageView
    private lateinit var nowTitle: TextView
    private lateinit var nowArtist: TextView
    private lateinit var playBtn: ImageButton
    private lateinit var compactNextBtn: ImageButton

    private lateinit var expandedPlayer: View
    private lateinit var bigCover: ImageView
    private lateinit var bigTitle: TextView
    private lateinit var bigArtist: TextView
    private lateinit var favBtn: ImageButton
    private lateinit var shuffleBtn: ImageButton
    private lateinit var repeatBtn: ImageButton
    private lateinit var playBtnExpanded: ImageButton
    private lateinit var visualizerView: VisualizerView
    private lateinit var hzText: TextView
    private lateinit var noteText: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var currentTime: TextView
    private lateinit var totalTime: TextView

    private val masterSongs = mutableListOf<Song>()
    private val displayedSongs = mutableListOf<Song>()
    private var adapter: SongAdapter? = null

    private var binder: PlaybackService.LocalBinder? = null
    private var bound = false
    private var currentSongId = -1L
    private var lastSnapshot: PlaybackService.PlaybackSnapshot? = null

    private var analyzer: SpectrumAnalyzer? = null
    private var analyzerGeneration = 0

    private var isSeeking = false
    private var showBassMeter = true
    private var showVisualizer = true
    private var appLanguage = "hu"
    private var sortMode = SortMode.TITLE
    private var artistFilter: String? = null
    private var favoritesOnly = false
    private var isExpanded = false

    private val favorites = mutableSetOf<Long>()
    private val prefs by lazy {
        getSharedPreferences("bass_player_prefs", Context.MODE_PRIVATE)
    }

    private val handler = Handler(Looper.getMainLooper())
    private val audioManager by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }

    private enum class SortMode {
        TITLE, ARTIST, ALBUM, NEWEST, LONGEST, SHORTEST;
        fun label(english: Boolean) = when (this) {
            TITLE -> if (english) "Title (A–Z)" else "Cím (A–Z)"
            ARTIST -> if (english) "Artist (A–Z)" else "Előadó (A–Z)"
            ALBUM -> if (english) "Album (A–Z)" else "Album (A–Z)"
            NEWEST -> if (english) "Newest first" else "Legújabb elöl"
            LONGEST -> if (english) "Longest first" else "Leghosszabb elöl"
            SHORTEST -> if (english) "Shortest first" else "Legrövidebb elöl"
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val localBinder = service as? PlaybackService.LocalBinder ?: return
            binder = localBinder
            bound = true
            localBinder.setListener(this@MainActivity)
            applySnapshot(localBinder.snapshot())
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            binder = null
            bound = false
        }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
            if (hasMediaPermission()) {
                loadSongs()
            } else {
                loading.visibility = View.GONE
                emptyState.visibility = View.VISIBLE
                emptyText.text = s("Engedélyezd a zenei fájlokhoz való hozzáférést a rendszer beállításaiban.", "Allow access to audio files in system settings.")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        val root = findViewById<View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }
        WindowInsetsControllerCompat(window, root).isAppearanceLightStatusBars = false

        loadFavorites()
        showBassMeter = prefs.getBoolean("show_bass_meter", true)
        showVisualizer = prefs.getBoolean("show_visualizer", true)
        appLanguage = prefs.getString("app_language", null)
            ?: if (Locale.getDefault().language.equals("hu", true)) "hu" else "en"
        bindViews()
        applyLocalizedUi()

        songList.layoutManager = LinearLayoutManager(this)
        adapter = SongAdapter(
            onClick = { index -> playSong(index) },
            onFavoriteClick = { song -> if (!isExpanded) toggleFavorite(song) }
        )
        adapter?.setEnglish(isEnglish)
        songList.adapter = adapter

        playBtn.setOnClickListener { binder?.toggle() ?: startPlaybackFromList() }
        compactNextBtn.setOnClickListener { binder?.next() }
        playBtnExpanded.setOnClickListener { binder?.toggle() ?: startPlaybackFromList() }
        findViewById<ImageButton>(R.id.prevBtn).setOnClickListener { binder?.previous() }
        findViewById<ImageButton>(R.id.nextBtn).setOnClickListener { binder?.next() }

        playerPanel.setOnClickListener { expandPlayer() }
        nowCover.setOnClickListener { expandPlayer() }
        findViewById<View>(R.id.collapseBtn).setOnClickListener { collapsePlayer() }
        bigCover.setOnClickListener { collapsePlayer() }

        attachSwipe(playerPanel, expand = true)
        attachSwipe(nowCover, expand = true)
        attachSwipe(bigCover, expand = false)

        shuffleBtn.setOnClickListener {
            val snapshot = lastSnapshot ?: binder?.snapshot() ?: return@setOnClickListener
            binder?.setShuffle(!snapshot.shuffle)
        }
        repeatBtn.setOnClickListener {
            binder?.cycleRepeat()
        }
        favBtn.setOnClickListener {
            val song = lastSnapshot?.song ?: return@setOnClickListener
            toggleFavorite(song)
        }
        findViewById<ImageButton>(R.id.analyzerSettingsBtn).setOnClickListener {
            showSettingsDialog()
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) currentTime.text = formatTime(progress.toLong())
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isSeeking = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val target = (seekBar?.progress ?: 0).toLong()
                binder?.seekTo(target)
                currentTime.text = formatTime(target)
                handler.postDelayed({ isSeeking = false }, 700)
            }
        })

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = applyFilters()
        })

        findViewById<ImageButton>(R.id.sortBtn).setOnClickListener { showSortDialog() }
        filterBtn.setOnClickListener { showArtistFilterDialog() }
        favoritesFilterBtn.setOnClickListener {
            favoritesOnly = !favoritesOnly
            applyFilters()
            updateFavoritesFilterButton()
        }

        updateFavoritesFilterButton()
        requestPermissions()
    }

    override fun onStart() {
        super.onStart()
        try {
            bindService(
                Intent(this, PlaybackService::class.java),
                serviceConnection,
                Context.BIND_AUTO_CREATE
            )
        } catch (_: Exception) {
        }
    }

    override fun onResume() {
        super.onResume()
        if (isExpanded) startAnalyzer()
    }

    override fun onStop() {
        stopAnalyzer()
        binder?.setListener(null)
        if (bound) {
            try {
                unbindService(serviceConnection)
            } catch (_: Exception) {
            }
        }
        bound = false
        binder = null
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        stopAnalyzer()
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (isExpanded) {
            collapsePlayer()
        } else if (::searchInput.isInitialized && searchInput.text.isNotEmpty()) {
            searchInput.text.clear()
            searchInput.clearFocus()
            (getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)
                ?.hideSoftInputFromWindow(searchInput.windowToken, 0)
        } else {
            super.onBackPressed()
        }
    }

    private fun bindViews() {
        songList = findViewById(R.id.songList)
        mainContent = findViewById(R.id.mainContent)
        countText = findViewById(R.id.countText)
        subtitleText = findViewById(R.id.subtitleText)
        searchInput = findViewById(R.id.searchInput)
        filterBtn = findViewById(R.id.filterBtn)
        favoritesFilterBtn = findViewById(R.id.favoritesFilterBtn)
        emptyState = findViewById(R.id.emptyState)
        emptyText = findViewById(R.id.emptyText)
        loading = findViewById(R.id.loading)

        playerPanel = findViewById(R.id.playerPanel)
        nowCover = findViewById(R.id.nowCover)
        nowTitle = findViewById(R.id.nowTitle)
        nowArtist = findViewById(R.id.nowArtist)
        playBtn = findViewById(R.id.playBtn)
        compactNextBtn = findViewById(R.id.compactNextBtn)

        expandedPlayer = findViewById(R.id.expandedPlayer)
        bigCover = findViewById(R.id.bigCover)
        bigTitle = findViewById(R.id.bigTitle)
        bigArtist = findViewById(R.id.bigArtist)
        favBtn = findViewById(R.id.favBtn)
        shuffleBtn = findViewById(R.id.shuffleBtn)
        repeatBtn = findViewById(R.id.repeatBtn)
        playBtnExpanded = findViewById(R.id.playBtnExpanded)
        visualizerView = findViewById(R.id.visualizerView)
        hzText = findViewById(R.id.hzText)
        noteText = findViewById(R.id.noteText)
        seekBar = findViewById(R.id.seekBar)
        currentTime = findViewById(R.id.currentTime)
        totalTime = findViewById(R.id.totalTime)
        updateAnalyzerVisibility()
    }

    private fun requestPermissions() {
        val missing = mutableListOf<String>()
        if (!hasMediaPermission()) missing.add(mediaPermission())

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (missing.isEmpty()) {
            loadSongs()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun mediaPermission(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    private fun hasMediaPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, mediaPermission()) ==
            PackageManager.PERMISSION_GRANTED

    private fun loadSongs() {
        loading.visibility = View.VISIBLE
        emptyState.visibility = View.GONE

        Thread {
            val loaded = querySongs()
            runOnUiThread {
                masterSongs.clear()
                masterSongs.addAll(loaded)
                applyFilters()
                loading.visibility = View.GONE
                binder?.snapshot()?.let { applySnapshot(it) }
            }
        }.start()
    }

    private fun querySongs(): List<Song> {
        val result = mutableListOf<Song>()
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATE_ADDED
        )
        val selection = buildString {
            append("${MediaStore.Audio.Media.DURATION} > 3000")
            append(" AND ${MediaStore.Audio.Media.DURATION} <= 10800000")
            append(" AND ${MediaStore.Audio.Media.IS_RINGTONE} = 0")
            append(" AND ${MediaStore.Audio.Media.IS_ALARM} = 0")
            append(" AND ${MediaStore.Audio.Media.IS_NOTIFICATION} = 0")
            append(" AND (${MediaStore.Audio.Media.IS_MUSIC} != 0")
            append(" OR ${MediaStore.Audio.Media.MIME_TYPE} LIKE 'audio/%'")
            append(" OR ${MediaStore.Audio.Media.MIME_TYPE} LIKE 'application/ogg%')")
        }
        val sortOrder = "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"

        contentResolver.query(collection, projection, selection, null, sortOrder)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val albumIdColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)

            while (cursor.moveToNext()) {
                val duration = cursor.getLong(durationColumn)
                if (duration <= 3000 || duration > 10800000) continue

                val id = cursor.getLong(idColumn)
                val title = cursor.getString(titleColumn)?.trim()
                    ?.takeIf { it.isNotEmpty() } ?: "Névtelen szám"
                val rawArtist = cursor.getString(artistColumn)?.trim()
                val artist = if (rawArtist.isNullOrEmpty() || rawArtist == "<unknown>") {
                    "Ismeretlen előadó"
                } else {
                    rawArtist
                }
                val album = cursor.getString(albumColumn)?.trim()
                    ?.takeIf { it.isNotEmpty() && it != "<unknown>" } ?: ""
                val dateAdded = cursor.getLong(dateColumn)

                result.add(
                    Song(
                        id = id,
                        title = title,
                        artist = artist,
                        album = album,
                        durationMs = duration,
                        albumId = cursor.getLong(albumIdColumn),
                        dateAdded = dateAdded,
                        uri = ContentUris.withAppendedId(collection, id)
                    )
                )
            }
        }

        return result
    }

    private fun applyFilters() {
        val query = searchInput.text.toString().trim().lowercase(Locale.getDefault())
        val filtered = masterSongs.filter { song ->
            val matchesArtist = artistFilter == null || artistNames(song.artist).any {
                it.equals(artistFilter, ignoreCase = true)
            }
            val matchesFavorite = !favoritesOnly || favorites.contains(song.id)
            val matchesQuery = query.isEmpty() ||
                song.title.lowercase(Locale.getDefault()).contains(query) ||
                song.artist.lowercase(Locale.getDefault()).contains(query) ||
                song.album.lowercase(Locale.getDefault()).contains(query)
            matchesArtist && matchesFavorite && matchesQuery
        }

        val sorted = sortSongs(filtered)

        displayedSongs.clear()
        displayedSongs.addAll(sorted)
        adapter?.submit(displayedSongs.toList(), favorites)

        updateSummary()

        if (displayedSongs.isEmpty()) {
            emptyState.visibility = View.VISIBLE
            emptyText.text = when {
                masterSongs.isEmpty() ->
                    s("Nem található zene. Ellenőrizd, hogy vannak-e zenék a készüléken.", "No music found. Check that audio files are stored on this device.")
                favoritesOnly ->
                    s("Még nincs kedvenced. Koppints egy szám melletti csillagra.", "You have no favorites yet. Tap a song’s star to add one.")
                else ->
                    s("Nincs a keresésnek vagy a szűrésnek megfelelő szám.", "No songs match your search or filters.")
            }
        } else {
            emptyState.visibility = View.GONE
        }

        binder?.snapshot()?.song?.let { adapter?.setSelectedId(it.id) }
        updateFilterButton()
        updateFavoritesFilterButton()
    }

    private fun sortSongs(list: List<Song>): List<Song> = when (sortMode) {
        SortMode.TITLE -> list.sortedWith(
            compareBy<Song> { it.title.lowercase(Locale.getDefault()) }
                .thenBy { it.artist.lowercase(Locale.getDefault()) }
        )

        SortMode.ARTIST -> list.sortedWith(
            compareBy<Song> { it.artist.lowercase(Locale.getDefault()) }
                .thenBy { it.title.lowercase(Locale.getDefault()) }
        )

        SortMode.ALBUM -> list.sortedWith(
            compareBy<Song> { it.album.lowercase(Locale.getDefault()) }
                .thenBy { it.title.lowercase(Locale.getDefault()) }
        )

        SortMode.NEWEST -> list.sortedByDescending { it.dateAdded }
        SortMode.LONGEST -> list.sortedByDescending { it.durationMs }
        SortMode.SHORTEST -> list.sortedBy { it.durationMs }
    }

    private fun updateSummary() {
        val count = displayedSongs.size
        val totalMs = displayedSongs.sumOf { it.durationMs }
        countText.text = if (isEnglish) "$count songs" else "$count szám"
        subtitleText.text = if (isEnglish) "All $count songs · ${formatTotalDuration(totalMs)}" else "Összesen $count szám · ${formatTotalDuration(totalMs)}"
    }

    private fun updateFilterButton() {
        val color = if (artistFilter != null) {
            ContextCompat.getColor(this, R.color.green_bright)
        } else {
            ContextCompat.getColor(this, R.color.text)
        }
        filterBtn.setColorFilter(color)
    }

    private fun updateFavoritesFilterButton() {
        favoritesFilterBtn.setImageResource(
            if (favoritesOnly) R.drawable.ic_star_filled else R.drawable.ic_star
        )
        favoritesFilterBtn.setColorFilter(
            ContextCompat.getColor(
                this,
                if (favoritesOnly) R.color.green_bright else R.color.text
            )
        )
    }

    private fun showSortDialog() {
        val modes = SortMode.entries.toTypedArray()
        showModernChoiceDialog(s("Rendezés", "Sort by"), modes.map { it.label(isEnglish) }, sortMode.ordinal) { which ->
            sortMode = modes[which]
            applyFilters()
        }
    }

    private fun showArtistFilterDialog() {
        val artists = masterSongs.flatMap { artistNames(it.artist) }.distinctBy { it.lowercase(Locale.ROOT) }.sorted()
        if (artists.isEmpty()) return

        val options = ArrayList<String>(artists.size + 1)
        options.add(s("Minden előadó", "All artists"))
        options.addAll(artists)
        val selectedIndex = artistFilter?.let { selected -> artists.indexOfFirst { it.equals(selected, true) } + 1 } ?: 0

        showModernChoiceDialog(s("Előadó szűrése", "Filter by artist"), options, selectedIndex) { which ->
            artistFilter = if (which == 0) null else artists[which - 1]
            applyFilters()
        }
    }

    private fun showModernChoiceDialog(title: String, choices: List<String>, selected: Int, onChoose: (Int) -> Unit) {
        val panel = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(10))
        }
        val scroll = android.widget.ScrollView(this).apply { addView(panel) }
        val dialog = AlertDialog.Builder(this).setTitle(title).setView(scroll)
            .setNegativeButton(s("Bezárás", "Close"), null).create()
        choices.forEachIndexed { index, label ->
            val chosen = index == selected
            val row = android.widget.TextView(this).apply {
                text = if (chosen) "✓   $label" else "     $label"
                textSize = 15f
                setTextColor(ContextCompat.getColor(this@MainActivity, if (chosen) R.color.green_bright else R.color.text))
                setPadding(dp(14), dp(14), dp(14), dp(14))
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat()
                    setColor(ContextCompat.getColor(this@MainActivity, if (chosen) R.color.card else R.color.bg))
                }
                isClickable = true
                isFocusable = true
                setOnClickListener { dialog.dismiss(); onChoose(index) }
            }
            val params = android.widget.LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(7) }
            panel.addView(row, params)
        }
        dialog.show()
        styleDialog(dialog)
    }

    private val isEnglish get() = appLanguage == "en"
    private fun s(hungarian: String, english: String) = if (isEnglish) english else hungarian

    private fun showSettingsDialog() {
        val panel = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(8))
        }
        fun checkRow(label: String, checked: Boolean): android.widget.CheckBox =
            android.widget.CheckBox(this).apply {
                text = label
                isChecked = checked
                textSize = 15f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text))
                buttonTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this@MainActivity, R.color.green_bright))
                setPadding(dp(10), dp(12), dp(10), dp(12))
            }
        val meter = checkRow(s("Szubfrekvencia-mérő", "Bass frequency meter"), showBassMeter)
        val visual = checkRow(s("Vizualizátor", "Visualizer"), showVisualizer)
        panel.addView(meter)
        panel.addView(visual)
        val language = android.widget.TextView(this).apply {
            text = "${s("Nyelv", "Language")}: ${if (isEnglish) "English" else "Magyar"}  ›"
            textSize = 15f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text))
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(ContextCompat.getColor(this@MainActivity, R.color.card))
            }
            setOnClickListener {
                showModernChoiceDialog(s("Nyelv kiválasztása", "Choose language"), listOf("Magyar", "English"), if (isEnglish) 1 else 0) { index ->
                    appLanguage = if (index == 0) "hu" else "en"
                    showBassMeter = meter.isChecked
                    showVisualizer = visual.isChecked
                    prefs.edit().putString("app_language", appLanguage)
                        .putBoolean("show_bass_meter", showBassMeter)
                        .putBoolean("show_visualizer", showVisualizer).apply()
                    recreate()
                }
            }
        }
        panel.addView(language, android.widget.LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        val dialog = AlertDialog.Builder(this)
            .setTitle(s("Beállítások", "Settings"))
            .setView(panel)
            .setPositiveButton(s("Kész", "Done")) { _, _ ->
                showBassMeter = meter.isChecked
                showVisualizer = visual.isChecked
                prefs.edit().putBoolean("show_bass_meter", showBassMeter)
                    .putBoolean("show_visualizer", showVisualizer).apply()
                updateAnalyzerVisibility()
            }
            .setNegativeButton(s("Mégse", "Cancel"), null)
            .create()
        dialog.show()
        styleDialog(dialog)
    }

    private fun styleDialog(dialog: AlertDialog) {
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(24).toFloat()
            setColor(ContextCompat.getColor(this@MainActivity, R.color.bg))
        })
    }

    private fun applyLocalizedUi() {
        if (!::searchInput.isInitialized) return
        searchInput.hint = s("Keresés...", "Search...")
        findViewById<TextView>(R.id.subtitleText).text = s("Betöltés...", "Loading...")
        findViewById<TextView>(R.id.emptyText).text = s("Nem található zene a készüléken.", "No music found on this device.")
        findViewById<TextView>(R.id.nowPlayingLabel).text = s("MOST JÁTSZIK", "NOW PLAYING")
        findViewById<TextView>(R.id.bassCaption).text = s("ÉLŐ SZUBFREKVENCIA", "LIVE SUB FREQUENCY")
        findViewById<ImageButton>(R.id.sortBtn).contentDescription = s("Rendezés", "Sort")
        findViewById<ImageButton>(R.id.filterBtn).contentDescription = s("Előadó szűrése", "Filter artists")
        findViewById<ImageButton>(R.id.favoritesFilterBtn).contentDescription = s("Csak kedvencek", "Favorites only")
        updateAnalyzerVisibility()
    }

    private fun updateAnalyzerVisibility() {
        if (!::hzText.isInitialized) return
        val meterVisibility = if (showBassMeter) View.VISIBLE else View.GONE
        hzText.visibility = meterVisibility
        noteText.visibility = meterVisibility
        findViewById<View>(R.id.bassCaption)?.visibility = meterVisibility
        visualizerView.visibility = if (showVisualizer) View.VISIBLE else View.GONE
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun artistNames(raw: String): List<String> = raw.split(Regex("\\s*[;,/&]\\s*|\\s+feat\\.?\\s+|\\s+ft\\.?\\s+", RegexOption.IGNORE_CASE))
        .map { it.trim() }.filter { it.isNotEmpty() && !it.equals("Unknown artist", true) }

    private fun startPlaybackFromList() {
        if (binder == null || masterSongs.isEmpty()) return
        // A teljes könyvtár megy sorban, nem csak a szűrt találatok.
        val queue = sortSongs(masterSongs)
        val target = queue.indexOfFirst { it.id == currentSongId }
            .takeIf { it >= 0 } ?: 0
        binder?.playQueue(queue, target)
    }

    private fun playSong(index: Int) {
        // Nagy (now playing) nézetben a lista nem kaphat kattintást.
        if (isExpanded) return
        val song = displayedSongs.getOrNull(index) ?: return
        val localBinder = binder
        if (localBinder == null) {
            Toast.makeText(this, s("A lejátszó még indul, próbáld újra.", "Player is starting. Try again in a moment."), Toast.LENGTH_SHORT).show()
            return
        }
        // Keresés/szűrés után is a teljes könyvtár legyen a lejátszási sor,
        // a kiválasztott szám pozíciójától, hogy a "következő" vigye tovább.
        val queue = sortSongs(masterSongs)
        val target = queue.indexOfFirst { it.id == song.id }.takeIf { it >= 0 } ?: 0
        localBinder.playQueue(queue, target)
    }

    override fun onStateChanged(state: PlaybackService.PlaybackSnapshot) {
        applySnapshot(state)
    }

    override fun onProgress(positionMs: Long, durationMs: Long) {
        if (isSeeking) return
        if (!::seekBar.isInitialized) return
        seekBar.max = durationMs.toInt().coerceAtLeast(1)
        seekBar.progress = positionMs.toInt().coerceIn(0, seekBar.max)
        currentTime.text = formatTime(positionMs)
        totalTime.text = formatTime(durationMs)
    }

    override fun onError(message: String) {
        val displayMessage = if (isEnglish) when (message) {
            "Nem sikerült lejátszani ezt a számot." -> "Couldn't play this song."
            "Nem sikerült megnyitni a zenét." -> "Couldn't open this audio file."
            else -> message
        } else message
        Toast.makeText(this, displayMessage, Toast.LENGTH_SHORT).show()
    }

    private fun applySnapshot(state: PlaybackService.PlaybackSnapshot) {
        lastSnapshot = state
        updatePlayIcon(state.isPlaying)
        updatePlaybackModes(state)

        val song = state.song
        if (song == null) {
            playerPanel.visibility = View.GONE
            if (isExpanded) collapsePlayer()
            return
        }

        playerPanel.visibility = View.VISIBLE
        val subtitle = if (song.album.isNotBlank()) {
            "${song.artist} · ${song.album}"
        } else {
            song.artist
        }
        nowTitle.text = song.title
        nowArtist.text = subtitle
        bigTitle.text = song.title
        bigArtist.text = subtitle
        updateFavoriteUi(song)

        val duration = if (state.durationMs > 0) state.durationMs else song.durationMs
        totalTime.text = formatTime(duration)
        if (!isSeeking) {
            seekBar.max = duration.toInt().coerceAtLeast(1)
            seekBar.progress = state.positionMs.toInt().coerceIn(0, seekBar.max)
            currentTime.text = formatTime(state.positionMs)
        }

        if (currentSongId != song.id) {
            currentSongId = song.id
            AlbumArtLoader.bind(nowCover, song, 320)
            AlbumArtLoader.bind(bigCover, song, 1024, preserveAspectRatio = true)
            hzText.text = "--.-- Hz"
            noteText.text = if (state.isPlaying) s("Elemzés...", "Analyzing...") else s("Megállítva", "Paused")
            visualizerView.reset()
            analyzer?.reset()
            adapter?.setSelectedId(song.id)
            val position = displayedSongs.indexOfFirst { it.id == song.id }
            if (position >= 0) songList.scrollToPosition(position)
        }

        if (isExpanded) {
            startAnalyzer()
            if (!state.isPlaying && state.isPrepared) {
                analyzer?.reset()
                hzText.text = "--.-- Hz"
                noteText.text = s("Megállítva", "Paused")
            }
        }
    }

    private fun updatePlayIcon(isPlaying: Boolean) {
        val icon = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        playBtn.setImageResource(icon)
        playBtnExpanded.setImageResource(icon)
        playBtn.contentDescription = if (isPlaying) s("Szünet", "Pause") else s("Lejátszás", "Play")
        playBtnExpanded.contentDescription = playBtn.contentDescription
    }

    private fun updatePlaybackModes(state: PlaybackService.PlaybackSnapshot) {
        shuffleBtn.setColorFilter(
            ContextCompat.getColor(
                this,
                if (state.shuffle) R.color.green_bright else R.color.subtext
            )
        )

        repeatBtn.setImageResource(
            if (state.repeatMode == PlaybackService.REPEAT_ONE) {
                R.drawable.ic_repeat_one
            } else {
                R.drawable.ic_repeat
            }
        )
        repeatBtn.setColorFilter(
            ContextCompat.getColor(
                this,
                if (state.repeatMode == PlaybackService.REPEAT_OFF) {
                    R.color.subtext
                } else {
                    R.color.green_bright
                }
            )
        )
    }

    private fun updateFavoriteUi(song: Song?) {
        val favorite = song != null && favorites.contains(song.id)
        favBtn.setImageResource(
            if (favorite) R.drawable.ic_star_filled else R.drawable.ic_star
        )
        favBtn.setColorFilter(
            ContextCompat.getColor(
                this,
                if (favorite) R.color.green_bright else R.color.text
            )
        )
    }

    private fun toggleFavorite(song: Song) {
        if (!favorites.add(song.id)) {
            favorites.remove(song.id)
        }
        saveFavorites()
        adapter?.notifyFavoriteChanged(song.id)
        updateFavoriteUi(lastSnapshot?.song)
        if (favoritesOnly) applyFilters()
    }

    private fun loadFavorites() {
        prefs.getStringSet(KEY_FAVORITES, emptySet())?.forEach { value ->
            value.toLongOrNull()?.let { favorites.add(it) }
        }
    }

    private fun saveFavorites() {
        prefs.edit()
            .putStringSet(KEY_FAVORITES, favorites.map { it.toString() }.toSet())
            .apply()
    }

    private fun expandPlayer() {
        val snapshot = binder?.snapshot() ?: lastSnapshot ?: return
        if (snapshot.song == null) return
        isExpanded = true
        // A lista ne kaphasson érintést a nagy nézet alatt.
        mainContent.visibility = View.INVISIBLE
        expandedPlayer.visibility = View.VISIBLE
        applySnapshot(snapshot)
        startAnalyzer()
    }

    private fun collapsePlayer() {
        isExpanded = false
        expandedPlayer.visibility = View.GONE
        mainContent.visibility = View.VISIBLE
        stopAnalyzer()
    }

    private fun attachSwipe(view: View, expand: Boolean) {
        val threshold = 56f * resources.displayMetrics.density
        var startY = 0f
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> startY = event.y
                MotionEvent.ACTION_UP -> {
                    val delta = event.y - startY
                    if (expand && delta < -threshold) {
                        expandPlayer()
                    } else if (!expand && delta > threshold) {
                        collapsePlayer()
                    }
                }
            }
            false
        }
    }

    private fun startAnalyzer() {
        if (analyzer != null) return
        val generation = ++analyzerGeneration
        try {
            visualizerView.reset()
            val newAnalyzer = SpectrumAnalyzer(outputSampleRate()) { spectrum, hz, note ->
                runOnUiThread {
                    if (generation != analyzerGeneration) return@runOnUiThread
                    visualizerView.updateSpectrum(spectrum)
                    if (showBassMeter) {
                        hzText.text = if (hz > 0f) String.format(Locale.US, "%.1f Hz", hz) else "--.-- Hz"
                        noteText.text = if (hz > 0f) note else if (lastSnapshot?.isPlaying == true) s("Nincs szubfrekvencia", "No sub frequency") else s("Megállítva", "Paused")
                    }
                }
            }
            analyzer = newAnalyzer
            AudioTap.sink = AudioTap.Sink(newAnalyzer::pushSamples)
            newAnalyzer.start()
        } catch (_: Exception) {
            AudioTap.sink = null
            analyzer = null
        }
    }

    private fun stopAnalyzer() {
        analyzerGeneration++
        AudioTap.sink = null
        analyzer?.stop()
        analyzer?.reset()
        analyzer = null
        if (::visualizerView.isInitialized) visualizerView.reset()
    }
    private fun outputSampleRate(): Int {
        val value = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
        return value?.toIntOrNull()?.takeIf { it in 8000..192000 } ?: 48000
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%d:%02d", minutes, seconds)
        }
    }

    private fun formatTotalDuration(ms: Long): String {
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return when {
            hours > 0 -> if (isEnglish) "$hours hr $minutes min $seconds sec" else "$hours óra $minutes perc $seconds másodperc"
            minutes > 0 -> if (isEnglish) "$minutes min $seconds sec" else "$minutes perc $seconds másodperc"
            else -> if (isEnglish) "$seconds sec" else "$seconds másodperc"
        }
    }

    private companion object {
        const val KEY_FAVORITES = "favorite_ids"
    }
}



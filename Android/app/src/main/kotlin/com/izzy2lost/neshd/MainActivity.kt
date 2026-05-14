package com.izzy2lost.neshd

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.documentfile.provider.DocumentFile
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.izzy2lost.neshd.databinding.ActivityMainBinding
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URL
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val romList = mutableListOf<RomEntry>()
    private lateinit var adapter: RomLibraryAdapter

    private var pendingHdPackFolderName: String? = null
    private var isFabMenuVisible by mutableStateOf(true)
    private var useCoverGrid = false

    private lateinit var drawerBackCallback: OnBackPressedCallback

    private companion object {
        const val VIEW_TYPE_LIST = 0
        const val VIEW_TYPE_GRID = 1
        const val PER_GAME_VIDEO_FILTER_PREFIX = "per_game_video_filter_"
        const val PER_GAME_ASPECT_RATIO_PREFIX = "per_game_aspect_ratio_"
    }

    data class RomEntry(val name: String, val path: String)

    data class HdPackZipInfo(
        val displayName: String,
        val defaultFolderName: String,
        val stripPrefix: String
    )

    private val supportedRomExtensions = setOf(
        ".nes", ".fds", ".unif", ".unf", ".nsf", ".nsfe", ".studybox", ".qd"
    )

    private val prefs by lazy { getSharedPreferences(NativeLib.PREFS_NAME, MODE_PRIVATE) }
    private val coverRepoBaseUrl = "https://raw.githubusercontent.com/izzy2lost/nes_covers/main/"
    private val coverRepoBaseUrlJp = "https://raw.githubusercontent.com/izzy2lost/nes_covers_jp/main/"
    private val coverRepoBaseUrlFds = "https://raw.githubusercontent.com/izzy2lost/fds_covers_jp/main/"
    private val coverIndexLock = Any()
    private val coverIndex = HashMap<String, String>()
    private val coverIndexJp = HashMap<String, String>()
    private val coverIndexFds = HashMap<String, String>()
    private val coverMemoryCache = object : LinkedHashMap<String, Bitmap>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?) = size > 48
    }
    private val coverDownloads = mutableSetOf<String>()
    private val coverMisses = mutableSetOf<String>()
    private val coverIdentityLock = Any()
    private val coverTitleByRomPath = HashMap<String, String?>()
    private val coverRegionByRomPath = HashMap<String, NesRomDatabase.Region>()
    private val coverTitleLoads = mutableSetOf<String>()
    @Volatile private var coverIndexLoaded = false
    @Volatile private var coverIndexLoading = false

    private val pickRomLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { launchRomFromUri(it) }
    }

    private val pickFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let {
            persistReadPermission(it)
            scanFolder(it)
        }
    }

    private val pickHdPackLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        val folderName = pendingHdPackFolderName ?: return@registerForActivityResult
        pendingHdPackFolderName = null
        uri?.let { handleHdPackZipForGame(it, folderName) }
    }

    private val pickBiosLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { installFdsBios(it) }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(this, "Storage permission required to browse ROMs", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT)
        )
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        setupEdgeToEdgeInsets()
        setupBackCallbacks()
        setupThemeToggle()
        setupFabMenu()
        setupRomList()
        setupSettingsDrawer()

        NativeLib.initialize(AppStorage.nesHdHomeDir(this).absolutePath)
        applySavedNativeSettings()
        checkPermissions()

        if (savedInstanceState == null) {
            consumeLaunchRomExtra()
        } else {
            intent.removeExtra(EmulatorActivity.EXTRA_ROM_PATH)
        }
    }

    // ── Theme toggle ─────────────────────────────────────────────────────────

    private fun setupThemeToggle() {
        syncThemeToggleIcon()
        binding.btnThemeToggle.setOnClickListener {
            val newMode = if (isEffectivelyNightMode()) {
                AppCompatDelegate.MODE_NIGHT_NO
            } else {
                AppCompatDelegate.MODE_NIGHT_YES
            }
            prefs.edit().putInt(App.PREF_NIGHT_MODE, newMode).apply()
            AppCompatDelegate.setDefaultNightMode(newMode)
        }
    }

    private fun syncThemeToggleIcon() {
        binding.btnThemeToggle.setImageResource(
            if (isEffectivelyNightMode()) R.drawable.light_mode_24px else R.drawable.dark_mode_24px
        )
    }

    private fun isEffectivelyNightMode(): Boolean {
        return when (AppCompatDelegate.getDefaultNightMode()) {
            AppCompatDelegate.MODE_NIGHT_YES -> true
            AppCompatDelegate.MODE_NIGHT_NO -> false
            else -> resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
        }
    }

    // ── Edge-to-edge ─────────────────────────────────────────────────────────

    private data class InitialPadding(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    )

    private data class InitialMargins(
        val start: Int,
        val top: Int,
        val end: Int,
        val bottom: Int
    )

    private fun setupEdgeToEdgeInsets() {
        val appBarPadding = binding.appBarLayout.initialPadding()
        val romListPadding = binding.romRecyclerView.initialPadding()
        val drawerPadding = binding.settingsDrawerContainer.initialPadding()
        val fabMenuMargins = binding.fabMenu.initialMargins()

        ViewCompat.setOnApplyWindowInsetsListener(binding.drawerLayout) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            val leftInset = maxOf(systemBars.left, cutout.left)
            val topInset = maxOf(systemBars.top, cutout.top)
            val rightInset = maxOf(systemBars.right, cutout.right)
            val bottomInset = maxOf(systemBars.bottom, cutout.bottom)
            val endInset = if (binding.drawerLayout.layoutDirection == View.LAYOUT_DIRECTION_RTL) {
                leftInset
            } else {
                rightInset
            }

            binding.appBarLayout.updatePadding(
                left = appBarPadding.left + leftInset,
                top = appBarPadding.top + topInset,
                right = appBarPadding.right + rightInset,
                bottom = appBarPadding.bottom
            )

            binding.romRecyclerView.updatePadding(
                left = romListPadding.left + leftInset,
                top = romListPadding.top,
                right = romListPadding.right + rightInset,
                bottom = romListPadding.bottom + bottomInset
            )

            binding.settingsDrawerContainer.updatePadding(
                left = drawerPadding.left + leftInset,
                top = drawerPadding.top + topInset,
                right = drawerPadding.right + rightInset,
                bottom = drawerPadding.bottom + bottomInset
            )

            binding.fabMenu.applySystemBarMargins(fabMenuMargins, endInset, bottomInset)

            insets
        }
        ViewCompat.requestApplyInsets(binding.drawerLayout)
    }

    private fun View.initialPadding() =
        InitialPadding(paddingLeft, paddingTop, paddingRight, paddingBottom)

    private fun View.initialMargins(): InitialMargins {
        val params = layoutParams as ViewGroup.MarginLayoutParams
        return InitialMargins(
            params.marginStart,
            params.topMargin,
            params.marginEnd,
            params.bottomMargin
        )
    }

    private fun View.applySystemBarMargins(
        initialMargins: InitialMargins,
        endInset: Int,
        bottomInset: Int
    ) {
        updateLayoutParams<ViewGroup.MarginLayoutParams> {
            marginStart = initialMargins.start
            topMargin = initialMargins.top
            marginEnd = initialMargins.end + endInset
            bottomMargin = initialMargins.bottom + bottomInset
        }
    }

    // ── Back press ──────────────────────────────────────────────────────────

    private fun setupBackCallbacks() {
        drawerBackCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                binding.drawerLayout.closeDrawer(GravityCompat.END)
            }
        }
        onBackPressedDispatcher.addCallback(this, drawerBackCallback)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.searchBar.visibility == View.VISIBLE) {
                    hideSearchBar()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })
    }

    // ── FAB menu ─────────────────────────────────────────────────────────────

    private fun setupFabMenu() {
        binding.fabMenu.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        binding.fabMenu.setContent {
            MainFabMenu(
                visible = isFabMenuVisible,
                onOpenRom = {
                    pickRomLauncher.launch(arrayOf("application/octet-stream", "*/*"))
                },
                onScanFolder = {
                    pickFolderLauncher.launch(null)
                },
                onSearch = {
                    showSearchBar()
                }
            )
        }
    }

    // ── ROM list ─────────────────────────────────────────────────────────────

    private fun setupRomList() {
        adapter = RomLibraryAdapter()
        useCoverGrid = prefs.getBoolean("library_cover_grid", false)

        binding.btnViewList.isCheckable = true
        binding.btnViewGrid.isCheckable = true
        binding.btnViewList.setOnClickListener { setDisplayMode(false) }
        binding.btnViewGrid.setOnClickListener { setDisplayMode(true) }

        binding.romRecyclerView.adapter = adapter
        syncDisplayMode()
        loadRomList()
        syncEmptyState()

        binding.romRecyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                when {
                    dy > dp(6) -> isFabMenuVisible = false
                    dy < -dp(6) -> isFabMenuVisible = true
                }
            }
        })

        ensureCoverIndexLoaded()
    }

    private fun setDisplayMode(grid: Boolean) {
        if (grid == useCoverGrid) return
        useCoverGrid = grid
        prefs.edit().putBoolean("library_cover_grid", useCoverGrid).apply()
        syncDisplayMode()
    }

    private fun syncDisplayMode() {
        binding.btnViewList.isChecked = !useCoverGrid
        binding.btnViewGrid.isChecked = useCoverGrid
        binding.romRecyclerView.layoutManager = createLibraryLayoutManager()
        adapter.setGridMode(useCoverGrid)
    }

    private fun createLibraryLayoutManager(): RecyclerView.LayoutManager {
        return if (useCoverGrid) {
            GridLayoutManager(this, resolveCoverGridSpanCount())
        } else {
            LinearLayoutManager(this)
        }
    }

    private fun resolveCoverGridSpanCount(): Int {
        val screenWidthDp = resources.configuration.screenWidthDp.takeIf { it > 0 }
            ?: (resources.displayMetrics.widthPixels / resources.displayMetrics.density).toInt()
        return (screenWidthDp / 160).coerceIn(2, 5)
    }

    private val searchWatcher = object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: android.text.Editable?) {
            searchQuery = s?.toString() ?: ""
            adapter.notifyDataSetChanged()
            syncEmptyState()
        }
    }

    private fun showSearchBar() {
        binding.searchBar.visibility = View.VISIBLE
        binding.searchInput.removeTextChangedListener(searchWatcher)
        binding.searchInput.addTextChangedListener(searchWatcher)
        binding.searchInput.requestFocus()
        val imm = getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        imm.showSoftInput(binding.searchInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        binding.btnClearSearch.setOnClickListener { hideSearchBar() }
    }

    private fun hideSearchBar() {
        binding.searchBar.visibility = View.GONE
        binding.searchInput.text?.clear()
        searchQuery = ""
        adapter.notifyDataSetChanged()
        syncEmptyState()
        val imm = getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        imm.hideSoftInputFromWindow(binding.searchInput.windowToken, 0)
    }

    private fun notifyLibraryChanged() {
        adapter.notifyDataSetChanged()
        syncEmptyState()
    }

    private fun syncEmptyState() {
        binding.libraryEmptyText.visibility = if (displayList.isEmpty()) View.VISIBLE else View.GONE
    }

    private var searchQuery = ""
    private val displayList get() = if (searchQuery.isBlank()) romList
        else romList.filter { it.name.contains(searchQuery, ignoreCase = true) }

    private inner class RomLibraryAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var gridMode = false

        fun setGridMode(grid: Boolean) {
            if (gridMode == grid) return
            gridMode = grid
            notifyDataSetChanged()
        }

        override fun getItemCount() = displayList.size

        override fun getItemViewType(position: Int) = if (gridMode) {
            VIEW_TYPE_GRID
        } else {
            VIEW_TYPE_LIST
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == VIEW_TYPE_GRID) {
                GridRomViewHolder(inflater.inflate(R.layout.rom_grid_item, parent, false))
            } else {
                ListRomViewHolder(inflater.inflate(R.layout.rom_list_item, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val rom = displayList[position]
            when (holder) {
                is ListRomViewHolder -> holder.bind(rom)
                is GridRomViewHolder -> holder.bind(rom)
            }
        }
    }

    private inner class ListRomViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val coverImage: ImageView = itemView.findViewById(R.id.romCoverImage)
        private val nameText: TextView = itemView.findViewById(R.id.romName)
        private val subtitleText: TextView = itemView.findViewById(R.id.romSubtitle)
        private val hdPackIcon: ImageView = itemView.findViewById(R.id.hdPackIcon)

        fun bind(rom: RomEntry) {
            nameText.text = romNameWithoutExtension(rom.name)
            subtitleText.text = buildRomSubtitle(rom)
            hdPackIcon.visibility = if (hasInstalledHdPack(rom)) View.VISIBLE else View.GONE
            itemView.setOnClickListener { launchRom(rom) }
            itemView.setOnLongClickListener {
                showGameOptions(rom)
                true
            }
            bindCoverArt(coverImage, rom)
        }
    }

    private inner class GridRomViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val coverImage: ImageView = itemView.findViewById(R.id.romCoverImage)
        private val nameText: TextView = itemView.findViewById(R.id.romGridName)
        private val hdPackIcon: ImageView = itemView.findViewById(R.id.romGridHdPackIcon)

        fun bind(rom: RomEntry) {
            nameText.text = romNameWithoutExtension(rom.name)
            hdPackIcon.visibility = if (hasInstalledHdPack(rom)) View.VISIBLE else View.GONE
            itemView.setOnClickListener { launchRom(rom) }
            itemView.setOnLongClickListener {
                showGameOptions(rom)
                true
            }
            bindCoverArt(coverImage, rom)
        }
    }

    // ── Per-game options (long press) ─────────────────────────────────────────

    private fun showGameOptions(rom: RomEntry) {
        MaterialAlertDialogBuilder(this)
            .setTitle(rom.name)
            .setItems(
                arrayOf(
                    "Install HD Pack",
                    "Load Save",
                    "Resolution",
                    "Aspect Ratio",
                    "Cheat Manager"
                )
            ) { _, which ->
                when (which) {
                    0 -> startHdPackInstall(rom)
                    1 -> launchRom(rom, showLoadStateDialog = true)
                    2 -> showPerGameVideoFilterDialog(rom)
                    3 -> showPerGameAspectRatioDialog(rom)
                    4 -> showCheatManager(rom)
                }
            }
            .show()
    }

    private fun showPerGameVideoFilterDialog(rom: RomEntry) {
        val prefs = getSharedPreferences(NativeLib.PREFS_NAME, MODE_PRIVATE)
        val filters = NativeLib.VIDEO_FILTER_OPTIONS
        val globalLabel = videoFilterLabel(NativeLib.getSavedVideoFilter(prefs))
        val currentOverride = getPerGameVideoFilter(rom)
        val items = arrayOf("Use global ($globalLabel)") + filters.map { it.label }
        val overrideIndex = currentOverride?.let { filterType ->
            filters.indexOfFirst { it.filterType == filterType }.takeIf { it >= 0 }?.plus(1)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Resolution")
            .setSingleChoiceItems(items, overrideIndex ?: 0) { dialog, which ->
                if (which == 0) {
                    savePerGameVideoFilter(rom, null)
                    Toast.makeText(this, "Using global resolution", Toast.LENGTH_SHORT).show()
                } else {
                    val option = filters[which - 1]
                    savePerGameVideoFilter(rom, option.filterType)
                    Toast.makeText(this, "Resolution set to ${option.label}", Toast.LENGTH_SHORT).show()
                }
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showPerGameAspectRatioDialog(rom: RomEntry) {
        val prefs = getSharedPreferences(NativeLib.PREFS_NAME, MODE_PRIVATE)
        val aspectRatios = NativeLib.ASPECT_RATIO_OPTIONS
        val globalLabel = aspectRatioLabel(NativeLib.getSavedAspectRatio(prefs))
        val currentOverride = getPerGameAspectRatio(rom)
        val items = arrayOf("Use global ($globalLabel)") + aspectRatios.map { it.label }
        val overrideIndex = currentOverride?.let { aspectRatio ->
            aspectRatios.indexOfFirst { it.aspectRatio == aspectRatio }.takeIf { it >= 0 }?.plus(1)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Aspect Ratio")
            .setSingleChoiceItems(items, overrideIndex ?: 0) { dialog, which ->
                if (which == 0) {
                    savePerGameAspectRatio(rom, null)
                    Toast.makeText(this, "Using global aspect ratio", Toast.LENGTH_SHORT).show()
                } else {
                    val option = aspectRatios[which - 1]
                    savePerGameAspectRatio(rom, option.aspectRatio)
                    Toast.makeText(this, "Aspect ratio set to ${option.label}", Toast.LENGTH_SHORT).show()
                }
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun getPerGameVideoFilter(rom: RomEntry): Int? {
        val key = perGameSettingsKey(PER_GAME_VIDEO_FILTER_PREFIX, rom)
        val prefs = getSharedPreferences(NativeLib.PREFS_NAME, MODE_PRIVATE)
        if (!prefs.contains(key)) return null

        val filterType = prefs.getInt(key, NativeLib.DEFAULT_VIDEO_FILTER)
        return NativeLib.VIDEO_FILTER_OPTIONS.firstOrNull { it.filterType == filterType }?.filterType
    }

    private fun savePerGameVideoFilter(rom: RomEntry, filterType: Int?) {
        val key = perGameSettingsKey(PER_GAME_VIDEO_FILTER_PREFIX, rom)
        val editor = getSharedPreferences(NativeLib.PREFS_NAME, MODE_PRIVATE).edit()
        if (filterType == null) {
            editor.remove(key)
        } else {
            editor.putInt(key, filterType)
        }
        editor.apply()
    }

    private fun getPerGameAspectRatio(rom: RomEntry): Int? {
        val key = perGameSettingsKey(PER_GAME_ASPECT_RATIO_PREFIX, rom)
        val prefs = getSharedPreferences(NativeLib.PREFS_NAME, MODE_PRIVATE)
        if (!prefs.contains(key)) return null

        val aspectRatio = prefs.getInt(key, NativeLib.DEFAULT_ASPECT_RATIO)
        return NativeLib.ASPECT_RATIO_OPTIONS.firstOrNull { it.aspectRatio == aspectRatio }?.aspectRatio
    }

    private fun savePerGameAspectRatio(rom: RomEntry, aspectRatio: Int?) {
        val key = perGameSettingsKey(PER_GAME_ASPECT_RATIO_PREFIX, rom)
        val editor = getSharedPreferences(NativeLib.PREFS_NAME, MODE_PRIVATE).edit()
        if (aspectRatio == null) {
            editor.remove(key)
        } else {
            editor.putInt(key, aspectRatio)
        }
        editor.apply()
    }

    private fun perGameSettingsKey(prefix: String, rom: RomEntry) =
        prefix + sha1(rom.path)

    private fun videoFilterLabel(filterType: Int) =
        NativeLib.VIDEO_FILTER_OPTIONS.firstOrNull { it.filterType == filterType }?.label
            ?: "Native 256x240"

    private fun aspectRatioLabel(aspectRatio: Int) =
        NativeLib.ASPECT_RATIO_OPTIONS.firstOrNull { it.aspectRatio == aspectRatio }?.label
            ?: "Native"

    private fun showCheatManager(rom: RomEntry) {
        Toast.makeText(this, "Loading cheats…", Toast.LENGTH_SHORT).show()
        thread(name = "CheatDatabaseLoad") {
            val sha1 = CheatDatabase.computeNesCheatHash(this, rom.path)
            val game = sha1?.let { CheatDatabase.findGame(this, it) }

            runOnUiThread {
                when {
                    sha1 == null -> Toast.makeText(
                        this,
                        "Could not read this ROM",
                        Toast.LENGTH_LONG
                    ).show()
                    game == null -> Toast.makeText(
                        this,
                        "No cheats found for this game",
                        Toast.LENGTH_LONG
                    ).show()
                    else -> showCheatManagerDialog(sha1, game)
                }
            }
        }
    }

    private fun showCheatManagerDialog(sha1: String, game: CheatDatabase.GameEntry) {
        val enabledCodes = CheatDatabase.enabledCheatCodes(this, sha1).toMutableSet()
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val scrollView = ScrollView(this).apply {
            addView(list)
        }

        game.cheats.forEach { cheat ->
            val row = TextView(this).apply {
                text = "${cheat.description}\n${cheat.code.replace(";", " + ")}"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setLineSpacing(0f, 1.08f)
                minHeight = dp(56)
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(24), dp(12), dp(24), dp(12))
                setBackgroundResource(selectableItemBackground())
            }

            fun syncColor() {
                row.setTextColor(if (cheat.code in enabledCodes) {
                    Color.rgb(46, 125, 50)
                } else {
                    Color.rgb(221, 32, 32)
                })
            }

            row.setOnClickListener {
                val enabled = if (cheat.code in enabledCodes) {
                    enabledCodes.remove(cheat.code)
                    false
                } else {
                    enabledCodes.add(cheat.code)
                    true
                }
                CheatDatabase.setCheatEnabled(this, sha1, cheat.code, enabled)
                syncColor()
            }

            syncColor()
            list.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Cheat Manager")
            .setView(scrollView)
            .setPositiveButton("Done", null)
            .show()
    }

    private fun startHdPackInstall(rom: RomEntry) {
        pendingHdPackFolderName = sanitizeFolderName(romNameWithoutExtension(rom.name))
        pickHdPackLauncher.launch(arrayOf(
            "application/zip",
            "application/x-zip-compressed",
            "application/octet-stream",
            "*/*"
        ))
    }

    private fun romNameWithoutExtension(name: String): String {
        return supportedRomExtensions.fold(name) { acc, ext ->
            if (acc.endsWith(ext, ignoreCase = true)) acc.dropLast(ext.length) else acc
        }
    }

    private fun buildRomSubtitle(rom: RomEntry): String {
        val format = romFormatLabel(rom.name)
        return if (hasInstalledHdPack(rom)) {
            "$format - HD pack installed"
        } else {
            format
        }
    }

    private fun romFormatLabel(name: String): String {
        val extension = supportedRomExtensions.firstOrNull { name.endsWith(it, ignoreCase = true) }
        return extension?.removePrefix(".")?.uppercase(Locale.US) ?: "NES"
    }

    private fun bindCoverArt(coverView: ImageView, rom: RomEntry) {
        val viewTag = "cover:${rom.path}"
        coverView.tag = viewTag
        showCoverPlaceholder(coverView)

        val lookupTitle = coverLookupTitleForRom(rom) ?: return
        val region = synchronized(coverIdentityLock) {
            coverRegionByRomPath[rom.path] ?: NesRomDatabase.Region.NA
        }
        val coverPath = lookupCoverPath(lookupTitle, region) ?: return
        val baseUrl = baseUrlFor(region)
        val cacheFile = coverCacheFile(coverPath, region)
        val memoryKey = cacheFile.nameWithoutExtension
        synchronized(coverMemoryCache) {
            coverMemoryCache[memoryKey]
        }?.let { bitmap ->
            applyCoverBitmap(coverView, viewTag, bitmap)
            return
        }

        if (cacheFile.isFile && cacheFile.length() > 0L) {
            loadCoverFileIntoView(coverView, viewTag, cacheFile, memoryKey)
            return
        }

        downloadCoverIntoCache(coverView, viewTag, baseUrl, coverPath, cacheFile, memoryKey)
    }

    private fun baseUrlFor(region: NesRomDatabase.Region) = when (region) {
        NesRomDatabase.Region.FDS -> coverRepoBaseUrlFds
        NesRomDatabase.Region.JP -> coverRepoBaseUrlJp
        NesRomDatabase.Region.NA -> coverRepoBaseUrl
    }

    private fun showCoverPlaceholder(coverView: ImageView) {
        coverView.background = null
        coverView.setImageResource(R.drawable.no_cover_placeholder)
    }

    private fun applyCoverBitmap(coverView: ImageView, expectedTag: String, bitmap: Bitmap) {
        if (coverView.tag != expectedTag) return
        coverView.background = null
        coverView.setImageBitmap(bitmap)
    }

    private fun loadCoverFileIntoView(
        coverView: ImageView,
        expectedTag: String,
        file: File,
        memoryKey: String
    ) {
        thread(name = "CoverDecode") {
            val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return@thread
            synchronized(coverMemoryCache) {
                coverMemoryCache[memoryKey] = bitmap
            }
            runOnUiThread {
                applyCoverBitmap(coverView, expectedTag, bitmap)
            }
        }
    }

    private fun downloadCoverIntoCache(
        coverView: ImageView,
        expectedTag: String,
        baseUrl: String,
        coverPath: String,
        cacheFile: File,
        memoryKey: String
    ) {
        val downloadKey = "$baseUrl|$coverPath"
        synchronized(coverDownloads) {
            if (!coverDownloads.add(downloadKey)) return
        }

        thread(name = "CoverDownload") {
            try {
                cacheFile.parentFile?.mkdirs()
                val tempFile = File(cacheFile.parentFile, "${cacheFile.name}.tmp")
                val encodedPath = Uri.encode(coverPath)
                val connection = URL(baseUrl + encodedPath).openConnection().apply {
                    connectTimeout = 5000
                    readTimeout = 10000
                }
                connection.getInputStream().use { input ->
                    tempFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                if (tempFile.length() <= 0L) {
                    tempFile.delete()
                    return@thread
                }
                if (cacheFile.exists()) cacheFile.delete()
                if (!tempFile.renameTo(cacheFile)) {
                    tempFile.copyTo(cacheFile, overwrite = true)
                    tempFile.delete()
                }
                val bitmap = BitmapFactory.decodeFile(cacheFile.absolutePath) ?: return@thread
                synchronized(coverMemoryCache) {
                    coverMemoryCache[memoryKey] = bitmap
                }
                runOnUiThread {
                    applyCoverBitmap(coverView, expectedTag, bitmap)
                    adapter.notifyDataSetChanged()
                }
            } catch (_: Exception) {
                cacheFile.delete()
            } finally {
                synchronized(coverDownloads) {
                    coverDownloads.remove(downloadKey)
                }
            }
        }
    }

    private fun coverCacheFile(coverPath: String, region: NesRomDatabase.Region): File {
        val dir = File(filesDir, "nes_3d_cover_cache")
        val regionTag = when (region) {
            NesRomDatabase.Region.FDS -> "fds"
            NesRomDatabase.Region.JP -> "jp"
            NesRomDatabase.Region.NA -> "na"
        }
        val fileName = "${regionTag}_${sha1(coverPath)}.webp"
        return File(dir, fileName)
    }

    private fun coverLookupTitleForRom(rom: RomEntry): String? {
        synchronized(coverIdentityLock) {
            if (coverTitleByRomPath.containsKey(rom.path)) {
                return coverTitleByRomPath[rom.path] ?: rom.name
            }
        }
        loadCoverTitleFromRomAsync(rom)
        return null
    }

    private fun loadCoverTitleFromRomAsync(rom: RomEntry) {
        synchronized(coverIdentityLock) {
            if (!coverTitleLoads.add(rom.path)) return
        }

        thread(name = "CoverRomIdentify") {
            var resolvedRegion: NesRomDatabase.Region = NesRomDatabase.Region.NA
            val title = try {
                val match = NesRomDatabase.findMatch(this, rom.path)
                if (match != null) {
                    resolvedRegion = match.region
                    match.title
                } else {
                    val sha1 = CheatDatabase.computeNesCheatHash(this, rom.path)
                    sha1?.let { CheatDatabase.findGame(this, it)?.name }
                }
            } catch (_: Exception) {
                null
            }

            synchronized(coverIdentityLock) {
                coverTitleByRomPath[rom.path] = title
                coverRegionByRomPath[rom.path] = resolvedRegion
                coverTitleLoads.remove(rom.path)
            }

            runOnUiThread {
                val index = romList.indexOfFirst { it.path == rom.path }
                if (index >= 0) {
                    adapter.notifyItemChanged(index)
                }
            }
        }
    }

    private fun lookupCoverPath(rawName: String, region: NesRomDatabase.Region): String? {
        ensureCoverIndexLoaded()
        if (!coverIndexLoaded) return null

        val candidates = coverLookupCandidates(rawName)
        val missKey = (region.name + ":" + (candidates.firstOrNull().orEmpty()))
        synchronized(coverIndexLock) {
            if (missKey.endsWith(":")) return null
            if (missKey in coverMisses) return null
            val indexes = when (region) {
                NesRomDatabase.Region.FDS -> listOf(coverIndexFds, coverIndexJp, coverIndex)
                NesRomDatabase.Region.JP -> listOf(coverIndexJp, coverIndex, coverIndexFds)
                NesRomDatabase.Region.NA -> listOf(coverIndex, coverIndexJp, coverIndexFds)
            }
            for (index in indexes) {
                for (candidate in candidates) {
                    index[candidate]?.let { return it }
                }
            }
            coverMisses.add(missKey)
        }
        return null
    }

    private fun ensureCoverIndexLoaded() {
        if (coverIndexLoaded || coverIndexLoading) return
        synchronized(coverIndexLock) {
            if (coverIndexLoaded || coverIndexLoading) return
            coverIndexLoading = true
        }

        thread(name = "CoverManifestLoad") {
            val parsedNa = loadManifestInto(coverRepoBaseUrl)
            val parsedJp = loadManifestInto(coverRepoBaseUrlJp)
            val parsedFds = loadManifestInto(coverRepoBaseUrlFds)

            synchronized(coverIndexLock) {
                coverIndex.clear()
                coverIndex.putAll(parsedNa)
                coverIndexJp.clear()
                coverIndexJp.putAll(parsedJp)
                coverIndexFds.clear()
                coverIndexFds.putAll(parsedFds)
                coverMisses.clear()
                coverIndexLoaded = true
                coverIndexLoading = false
            }
            runOnUiThread {
                notifyLibraryChanged()
            }
        }
    }

    private fun loadManifestInto(baseUrl: String): Map<String, String> {
        val parsed = HashMap<String, String>()
        try {
            val connection = URL(baseUrl + "manifest.json").openConnection().apply {
                connectTimeout = 5000
                readTimeout = 10000
            }
            val manifest = connection.getInputStream().bufferedReader().use { it.readText() }
            val items = JSONObject(manifest).getJSONArray("items")
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                val title = item.optString("title")
                val path = item.optString("path", item.optString("file"))
                if (title.isBlank() || path.isBlank()) continue
                addCoverIndexEntries(parsed, title, path)
                addCoverIndexEntries(parsed, path.removeSuffix(".webp"), path)
                val aliases = item.optJSONArray("aliases")
                if (aliases != null) {
                    for (aliasIndex in 0 until aliases.length()) {
                        addCoverIndexEntries(parsed, aliases.optString(aliasIndex), path)
                    }
                }
            }
        } catch (_: Exception) {
            // Covers are optional; library remains usable with placeholders.
        }
        return parsed
    }

    private fun addCoverIndexEntries(out: MutableMap<String, String>, title: String, path: String) {
        for (candidate in coverIndexCandidates(title)) {
            if (candidate.isNotBlank()) out.putIfAbsent(candidate, path)
        }
    }

    private fun coverIndexCandidates(rawName: String): LinkedHashSet<String> {
        val base = romNameWithoutExtension(rawName)
        val stripped = stripRomDecorations(base)
        val candidates = linkedSetOf(
            base,
            stripped,
            moveTrailingArticle(base),
            moveTrailingArticle(stripped)
        )
        return normalizedCoverCandidates(candidates)
    }

    private fun coverLookupCandidates(rawName: String): LinkedHashSet<String> {
        val base = romNameWithoutExtension(rawName)
        val stripped = stripRomDecorations(base)
        val candidates = linkedSetOf(
            base,
            stripped,
            moveTrailingArticle(base),
            moveTrailingArticle(stripped),
            stripped.substringBefore(" - ").trim(),
            stripped.substringBefore(":").trim()
        )
        return normalizedCoverCandidates(candidates)
    }

    private fun normalizedCoverCandidates(candidates: Iterable<String>): LinkedHashSet<String> {
        return candidates
            .asSequence()
            .map { normalizeCoverKey(it) }
            .filter { it.isNotBlank() }
            .toCollection(LinkedHashSet())
    }

    private fun stripRomDecorations(name: String): String {
        return name
            .replace('_', ' ')
            .replace(Regex("\\[[^\\]]*\\]"), " ")
            .replace(Regex("\\([^\\)]*\\)"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun moveTrailingArticle(name: String): String {
        val match = Regex("""^(.+),\s*(the|a|an)$""", RegexOption.IGNORE_CASE).matchEntire(name.trim())
            ?: return name
        return "${match.groupValues[2]} ${match.groupValues[1]}"
    }

    private fun normalizeCoverKey(input: String): String {
        val ascii = Normalizer.normalize(input, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
        return ascii
            .lowercase(Locale.US)
            .replace("&", " and ")
            .replace("+", " ")
            .replace("'", "")
            .replace(Regex("\\b(usa|world|rev|revision|prototype|proto|beta)\\b"), " ")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
            .replace(Regex("\\s+"), "")
    }

    private fun sha1(text: String): String {
        val digest = MessageDigest.getInstance("SHA-1")
            .digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    // ── Settings drawer ───────────────────────────────────────────────────────

    private fun setupSettingsDrawer() {
        binding.btnSettings.setOnClickListener {
            openSettingsDrawer()
        }

        binding.drawerSettings.videoFilterRow.setOnClickListener {
            showVideoFilterDialog()
        }

        binding.drawerSettings.aspectRatioRow.setOnClickListener {
            showAspectRatioDialog()
        }

        binding.drawerSettings.fdsBiosRow.setOnClickListener {
            pickBiosLauncher.launch(arrayOf("application/octet-stream", "*/*"))
        }

        binding.drawerLayout.addDrawerListener(object : DrawerLayout.DrawerListener {
            override fun onDrawerSlide(drawerView: View, slideOffset: Float) {}
            override fun onDrawerOpened(drawerView: View) {
                drawerBackCallback.isEnabled = true
                syncSettingsDrawer()
            }
            override fun onDrawerClosed(drawerView: View) {
                drawerBackCallback.isEnabled = false
            }
            override fun onDrawerStateChanged(newState: Int) {}
        })
    }

    private fun openSettingsDrawer() {
        syncSettingsDrawer()
        binding.drawerLayout.openDrawer(GravityCompat.END)
    }

    private fun syncSettingsDrawer() {
        val prefs = getSharedPreferences(NativeLib.PREFS_NAME, MODE_PRIVATE)
        val currentFilter = NativeLib.getSavedVideoFilter(prefs)
        val option = NativeLib.VIDEO_FILTER_OPTIONS.firstOrNull { it.filterType == currentFilter }
        binding.drawerSettings.videoFilterValue.text = option?.label ?: "Native 256×240"

        val currentAspectRatio = NativeLib.getSavedAspectRatio(prefs)
        val aspectRatioOption = NativeLib.ASPECT_RATIO_OPTIONS.firstOrNull {
            it.aspectRatio == currentAspectRatio
        }
        binding.drawerSettings.aspectRatioValue.text = aspectRatioOption?.label ?: "Native"

        // Temporarily null out listener to avoid spurious saves during sync
        binding.drawerSettings.switchHdPacks.setOnCheckedChangeListener(null)
        binding.drawerSettings.switchHdPacks.isChecked = prefs.getBoolean(NativeLib.PREF_HD_PACKS, true)
        binding.drawerSettings.switchHdPacks.setOnCheckedChangeListener { _, checked ->
            NativeLib.saveVideoFilter(prefs, NativeLib.getSavedVideoFilter(prefs), checked)
            NativeLib.setHdPacksEnabled(checked)
        }

        val biosInstalled = fdsBiosFile().exists()
        binding.drawerSettings.fdsBiosStatus.text = if (biosInstalled) "Installed" else "Not installed"
    }

    private fun fdsBiosFile(): File =
        File(AppStorage.nesHdHomeDir(this), "Firmware/disksys.rom")

    private fun installFdsBios(uri: Uri) {
        thread {
            val dest = fdsBiosFile()
            val ok = try {
                contentResolver.openInputStream(uri)?.use { src ->
                    dest.outputStream().use { src.copyTo(it) }
                } != null
            } catch (e: Exception) {
                dest.delete()
                false
            }
            runOnUiThread {
                if (ok) {
                    binding.drawerSettings.fdsBiosStatus.text = "Installed"
                    Toast.makeText(this, "FDS BIOS installed", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Failed to install FDS BIOS", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showVideoFilterDialog() {
        val filters = NativeLib.VIDEO_FILTER_OPTIONS
        val prefs = getSharedPreferences(NativeLib.PREFS_NAME, MODE_PRIVATE)
        val savedFilter = NativeLib.getSavedVideoFilter(prefs)
        val currentIndex = filters.indexOfFirst { it.filterType == savedFilter }.coerceAtLeast(0)

        MaterialAlertDialogBuilder(this)
            .setTitle("Video Filter")
            .setSingleChoiceItems(filters.map { it.label }.toTypedArray(), currentIndex) { dialog, which ->
                val filterType = filters[which].filterType
                val hdPacks = prefs.getBoolean(NativeLib.PREF_HD_PACKS, true)
                NativeLib.saveVideoFilter(prefs, filterType, hdPacks)
                NativeLib.setVideoFilter(filterType)
                binding.drawerSettings.videoFilterValue.text = filters[which].label
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAspectRatioDialog() {
        val aspectRatios = NativeLib.ASPECT_RATIO_OPTIONS
        val prefs = getSharedPreferences(NativeLib.PREFS_NAME, MODE_PRIVATE)
        val savedAspectRatio = NativeLib.getSavedAspectRatio(prefs)
        val currentIndex = aspectRatios.indexOfFirst {
            it.aspectRatio == savedAspectRatio
        }.coerceAtLeast(0)

        MaterialAlertDialogBuilder(this)
            .setTitle("Aspect Ratio")
            .setSingleChoiceItems(aspectRatios.map { it.label }.toTypedArray(), currentIndex) { dialog, which ->
                val aspectRatio = aspectRatios[which].aspectRatio
                NativeLib.saveAspectRatio(prefs, aspectRatio)
                NativeLib.setAspectRatio(aspectRatio)
                binding.drawerSettings.aspectRatioValue.text = aspectRatios[which].label
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── ROM launching ─────────────────────────────────────────────────────────

    private fun launchRomFromUri(uri: Uri) {
        copyRomToCache(uri)?.let { launchRomPath(it.absolutePath) }
    }

    private fun launchRom(
        rom: RomEntry,
        loadStateSlot: Int? = null,
        showLoadStateDialog: Boolean = false
    ) {
        if (!launchRom(
                rom.path,
                loadStateSlot,
                showLoadStateDialog,
                getPerGameVideoFilter(rom),
                getPerGameAspectRatio(rom)
            ) && romList.remove(rom)
        ) {
            notifyLibraryChanged()
            saveRomList()
        }
    }

    private fun launchRom(
        pathOrUri: String,
        loadStateSlot: Int? = null,
        showLoadStateDialog: Boolean = false,
        videoFilterOverride: Int? = null,
        aspectRatioOverride: Int? = null
    ): Boolean {
        val uri = Uri.parse(pathOrUri)
        val resolvedPath = when (uri.scheme) {
            "content" -> copyRomToCache(uri)?.absolutePath
            "file" -> uri.path
            else -> pathOrUri
        } ?: return false
        launchRomPath(
            resolvedPath,
            loadStateSlot,
            showLoadStateDialog,
            videoFilterOverride,
            aspectRatioOverride
        )
        return true
    }

    private fun launchRomPath(
        path: String,
        loadStateSlot: Int? = null,
        showLoadStateDialog: Boolean = false,
        videoFilterOverride: Int? = null,
        aspectRatioOverride: Int? = null
    ) {
        startActivity(Intent(this, EmulatorActivity::class.java).apply {
            putExtra(EmulatorActivity.EXTRA_ROM_PATH, path)
            loadStateSlot?.let { putExtra(EmulatorActivity.EXTRA_LOAD_STATE_SLOT, it) }
            videoFilterOverride?.let { putExtra(EmulatorActivity.EXTRA_VIDEO_FILTER_TYPE, it) }
            aspectRatioOverride?.let { putExtra(EmulatorActivity.EXTRA_ASPECT_RATIO, it) }
            if (showLoadStateDialog) {
                putExtra(EmulatorActivity.EXTRA_SHOW_LOAD_STATE_DIALOG, true)
            }
        })
    }

    private fun consumeLaunchRomExtra() {
        val romPath = intent.getStringExtra(EmulatorActivity.EXTRA_ROM_PATH) ?: return
        intent.removeExtra(EmulatorActivity.EXTRA_ROM_PATH)
        launchRom(romPath)
    }

    // ── ROM cache copy ────────────────────────────────────────────────────────

    private fun copyRomToCache(uri: Uri): File? {
        val name = getDisplayNameForUri(uri)
        val dest = File(
            File(cacheDir, "roms/${Integer.toHexString(uri.toString().hashCode())}").also { it.mkdirs() },
            name
        )
        return try {
            contentResolver.openInputStream(uri)?.use { src ->
                dest.outputStream().use { src.copyTo(it) }
            } ?: run {
                Toast.makeText(this, "Could not open ROM", Toast.LENGTH_LONG).show()
                return null
            }
            dest
        } catch (ex: Exception) {
            dest.delete()
            Toast.makeText(this, "Could not open ROM. It may have been moved or deleted.", Toast.LENGTH_LONG).show()
            null
        }
    }

    private fun getDisplayNameForUri(uri: Uri): String {
        val displayName = runCatching {
            DocumentFile.fromSingleUri(this, uri)?.name
        }.getOrNull()
        return sanitizeFileName(displayName ?: uri.lastPathSegment ?: "rom.nes")
    }

    // ── Folder scan ───────────────────────────────────────────────────────────

    private fun scanFolder(uri: Uri) {
        val tree = DocumentFile.fromTreeUri(this, uri) ?: return
        romList.clear()

        fun scanRecursive(dir: DocumentFile) {
            for (file in dir.listFiles()) {
                if (file.isDirectory) scanRecursive(file)
                else {
                    val n = file.name ?: continue
                    if (isSupportedRomName(n)) romList.add(RomEntry(n, file.uri.toString()))
                }
            }
        }

        scanRecursive(tree)
        romList.sortBy { it.name }
        notifyLibraryChanged()
        saveRomList()
        Toast.makeText(this, "Found ${romList.size} ROMs", Toast.LENGTH_SHORT).show()
    }

    // ── HD Pack import ────────────────────────────────────────────────────────

    private fun handleHdPackZipForGame(uri: Uri, folderName: String) {
        val info = try {
            inspectHdPackZip(uri)
        } catch (ex: IOException) {
            Toast.makeText(this, "Could not read HD pack zip: ${ex.message}", Toast.LENGTH_LONG).show()
            return
        }
        if (info == null) {
            Toast.makeText(this, "No hires.txt found in this zip", Toast.LENGTH_LONG).show()
            return
        }
        confirmAndImportHdPack(uri, info, folderName)
    }

    private fun inspectHdPackZip(uri: Uri): HdPackZipInfo? {
        val displayName = DocumentFile.fromSingleUri(this, uri)?.name
            ?: uri.lastPathSegment ?: "hdpack.zip"
        var hiresPath: String? = null

        contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input).use { zip ->
                var entry: ZipEntry? = zip.nextEntry
                while (entry != null) {
                    val n = normalizeZipPath(entry.name)
                    if (!entry.isDirectory &&
                        (n.equals("hires.txt", ignoreCase = true) ||
                                n.endsWith("/hires.txt", ignoreCase = true))) {
                        hiresPath = n
                        break
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        } ?: throw IOException("Could not open zip")

        val hires = hiresPath ?: return null
        val slashIdx = hires.lastIndexOf('/')
        val prefix = if (slashIdx >= 0) hires.substring(0, slashIdx + 1) else ""
        val folder = if (slashIdx >= 0) {
            hires.substring(0, slashIdx).substringAfterLast('/').ifBlank { displayName.removeZipExtension() }
        } else {
            displayName.removeZipExtension()
        }
        return HdPackZipInfo(displayName, sanitizeFolderName(folder).ifBlank { "HD Pack" }, prefix)
    }

    private fun confirmAndImportHdPack(uri: Uri, info: HdPackZipInfo, targetName: String) {
        val targetDir = AppStorage.hdPackDir(this, targetName)
        if (!targetDir.exists()) {
            importHdPack(uri, info, targetName, overwrite = false)
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Replace HD Pack?")
            .setMessage("An HD pack named “$targetName” already exists.")
            .setPositiveButton("Replace") { _, _ -> importHdPack(uri, info, targetName, overwrite = true) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun importHdPack(uri: Uri, info: HdPackZipInfo, targetName: String, overwrite: Boolean) {
        val targetDir = AppStorage.hdPackDir(this, targetName)
        Toast.makeText(this, "Installing “$targetName”…", Toast.LENGTH_SHORT).show()

        thread(name = "HdPackImport") {
            val message = try {
                if (overwrite && targetDir.exists()) targetDir.deleteRecursively()
                targetDir.mkdirs()

                var extracted = 0
                contentResolver.openInputStream(uri)?.use { input ->
                    ZipInputStream(input).use { zip ->
                        var entry: ZipEntry? = zip.nextEntry
                        while (entry != null) {
                            val normalized = normalizeZipPath(entry.name)
                            val relative = if (info.stripPrefix.isNotEmpty()) {
                                if (normalized.startsWith(info.stripPrefix))
                                    normalized.removePrefix(info.stripPrefix)
                                else ""
                            } else normalized

                            if (relative.isNotBlank()) {
                                val outName = if (relative.equals("hires.txt", ignoreCase = true))
                                    "hires.txt" else relative
                                val outFile = safeHdPackOutputFile(targetDir, outName)
                                if (entry.isDirectory) outFile.mkdirs()
                                else {
                                    outFile.parentFile?.mkdirs()
                                    outFile.outputStream().use { zip.copyTo(it) }
                                    extracted++
                                }
                            }
                            zip.closeEntry()
                            entry = zip.nextEntry
                        }
                    }
                } ?: throw IOException("Could not open zip")

                if (!File(targetDir, "hires.txt").isFile) {
                    targetDir.deleteRecursively()
                    "Import failed: hires.txt was not extracted"
                } else {
                    "Installed “$targetName” ($extracted files)"
                }
            } catch (ex: Exception) {
                if (!targetDir.exists() || targetDir.listFiles().isNullOrEmpty()) targetDir.deleteRecursively()
                "Could not import HD pack: ${ex.message}"
            }

            runOnUiThread {
                notifyLibraryChanged()
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    // ── ROM list persistence ──────────────────────────────────────────────────

    private fun saveRomList() {
        val encoded = romList.joinToString("\n") { "${it.name}\t${it.path}" }
        getSharedPreferences(NativeLib.PREFS_NAME, MODE_PRIVATE).edit()
            .putString("rom_list", encoded)
            .apply()
    }

    private fun loadRomList() {
        val encoded = getSharedPreferences(NativeLib.PREFS_NAME, MODE_PRIVATE)
            .getString("rom_list", null)?.takeIf { it.isNotBlank() } ?: return
        romList.clear()
        encoded.lines().forEach { line ->
            val tab = line.indexOf('\t')
            if (tab > 0) romList.add(RomEntry(line.substring(0, tab), line.substring(tab + 1)))
        }
        notifyLibraryChanged()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun sanitizeFileName(name: String) =
        name.replace(Regex("""[^\w. -]"""), "_").ifBlank { "rom.nes" }

    private fun sanitizeFolderName(name: String) =
        name.replace(Regex("""[^\w. -]"""), "_").trim()

    private fun isSupportedRomName(name: String) =
        supportedRomExtensions.any { name.endsWith(it, ignoreCase = true) }

    private fun hasInstalledHdPack(rom: RomEntry): Boolean {
        val folderName = sanitizeFolderName(romNameWithoutExtension(rom.name))
        val hdPackDir = AppStorage.hdPackDir(this, folderName)
        return File(hdPackDir, "hires.txt").isFile
    }

    private fun persistReadPermission(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {}
    }

    private fun safeHdPackOutputFile(root: File, relativePath: String): File {
        val out = File(root, relativePath)
        if (!out.canonicalPath.startsWith(root.canonicalPath + File.separator))
            throw IOException("Unsafe zip entry: $relativePath")
        return out
    }

    private fun normalizeZipPath(path: String) = path.replace('\\', '/').trimStart('/')

    private fun String.removeZipExtension() =
        replace(Regex("""(?i)\.zip$"""), "").ifBlank { "HD Pack" }

    private fun dp(value: Int) =
        (value * resources.displayMetrics.density).toInt()

    private fun selectableItemBackground(): Int {
        val value = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)
        return value.resourceId
    }

    private fun applySavedNativeSettings() {
        val prefs = getSharedPreferences(NativeLib.PREFS_NAME, MODE_PRIVATE)
        NativeLib.applyVideoSettings(
            NativeLib.getSavedVideoFilter(prefs),
            prefs.getBoolean(NativeLib.PREF_HD_PACKS, true),
            NativeLib.getSavedAspectRatio(prefs)
        )
    }

    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeLaunchRomExtra()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Do NOT release the emulator here; it lives across activities.
    }
}

package com.izzy2lost.neshd

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PorterDuff
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
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
import androidx.constraintlayout.widget.ConstraintLayout
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
import androidx.recyclerview.widget.LinearSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.izzy2lost.neshd.databinding.ActivityMainBinding
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import kotlin.concurrent.thread
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val romList = mutableListOf<RomEntry>()
    private lateinit var adapter: RomLibraryAdapter

    private var pendingHdPackFolderName: String? = null
    private var isFabMenuVisible by mutableStateOf(true)
    private var viewMode = LibraryViewMode.LIST

    private lateinit var carouselLayoutManager: LinearLayoutManager
    private val carouselSnapHelper = LinearSnapHelper()
    private var carouselAdapter: CarouselAdapter? = null
    // Which ROM sits in the middle of the carousel, so a refresh keeps it there.
    private var carouselCenteredPath: String? = null

    private lateinit var drawerBackCallback: OnBackPressedCallback

    private companion object {
        const val VIEW_TYPE_LIST = 0
        const val VIEW_TYPE_GRID = 1
        const val PREF_LIBRARY_VIEW_MODE = "library_view_mode"
        const val PREF_CAROUSEL_ROM_PATH = "library_carousel_rom_path"
        // Below this many games a looping carousel shows the same cover twice.
        const val CAROUSEL_MIN_LOOP_SIZE = 4
        const val CAROUSEL_CENTER_SCALE = 1.12f
        const val CAROUSEL_SIDE_SCALE = 0.72f
        const val CAROUSEL_SIDE_BRIGHTNESS = 0.42f
        const val CAROUSEL_SIDE_TILT_DEGREES = 28f
        const val CAROUSEL_CENTER_SCROLL_MS = 260
        // NES cover art width:height, matching the 452:701 ratio of the item layouts.
        const val COVER_ASPECT = 452f / 701f
        const val PER_GAME_VIDEO_FILTER_PREFIX = "per_game_video_filter_"
        const val PER_GAME_ASPECT_RATIO_PREFIX = "per_game_aspect_ratio_"
        const val PREF_ROM_FOLDER_URI = "rom_folder_uri"
        const val PRIVACY_POLICY_URL = "https://www.izzy2lost.com/neshd-privacy"
        const val LICENSE_URL = "https://github.com/izzy2lost/NESHD/blob/main/LICENSE"
        const val NES_HD_REPOSITORY_URL = "https://github.com/izzy2lost/NESHD"
        const val MESEN_REPOSITORY_URL = "https://github.com/SourMesen/Mesen2"
    }

    data class RomEntry(val name: String, val path: String)

    private enum class LibraryViewMode { LIST, GRID, CAROUSEL }

    private data class CoverMatch(val path: String, val region: NesRomDatabase.Region)

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
    /** Views waiting on a cover decode or download, by cover memory key. */
    private val coverWaiters = HashMap<String, MutableList<Pair<ImageView, String>>>()
    // Bounded, so a fling past dozens of games can't start dozens of downloads at once.
    private val coverDecodeExecutor = newestFirstExecutor(2)
    private val coverDownloadExecutor = newestFirstExecutor(4)
    private val coverIdentifyExecutor = newestFirstExecutor(2)
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
            prefs.edit().putString(PREF_ROM_FOLDER_URI, it.toString()).apply()
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
        compactLandscapeHeader()
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
        val carouselPadding = binding.romCarouselContainer.initialPadding()
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

            binding.romCarouselContainer.updatePadding(
                left = carouselPadding.left + leftInset,
                top = carouselPadding.top,
                right = carouselPadding.right + rightInset,
                bottom = carouselPadding.bottom + bottomInset
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

    /**
     * Landscape is short on height, so the view toggles move up into the toolbar row, beside
     * the settings button, and the hint is dropped. That height goes to the cover carousel.
     */
    private fun compactLandscapeHeader() {
        if (!isLandscape()) return
        val toolbarHeight = binding.toolbar.layoutParams.height
        (binding.libraryHint.parent as View).visibility = View.GONE
        binding.libraryControls.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        binding.libraryControls.updatePadding(top = 0, bottom = 0, right = toolbarHeight)
        binding.libraryControls.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            height = toolbarHeight
            topMargin = -toolbarHeight
        }
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
                onRefreshLibrary = {
                    refreshLibrary()
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
        viewMode = prefs.getString(PREF_LIBRARY_VIEW_MODE, null)
            ?.let { saved -> LibraryViewMode.entries.firstOrNull { it.name == saved } }
            ?: if (prefs.getBoolean("library_cover_grid", false)) LibraryViewMode.GRID else LibraryViewMode.LIST
        carouselCenteredPath = prefs.getString(PREF_CAROUSEL_ROM_PATH, null)

        binding.btnViewList.isCheckable = true
        binding.btnViewGrid.isCheckable = true
        binding.btnViewCarousel.isCheckable = true
        binding.btnViewList.setOnClickListener { setDisplayMode(LibraryViewMode.LIST) }
        binding.btnViewGrid.setOnClickListener { setDisplayMode(LibraryViewMode.GRID) }
        binding.btnViewCarousel.setOnClickListener { setDisplayMode(LibraryViewMode.CAROUSEL) }

        binding.romRecyclerView.adapter = adapter
        setupCarousel()
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

    private fun setDisplayMode(mode: LibraryViewMode) {
        if (mode == viewMode) {
            syncDisplayModeButtons()
            return
        }
        viewMode = mode
        prefs.edit().putString(PREF_LIBRARY_VIEW_MODE, viewMode.name).apply()
        syncDisplayMode()
    }

    private fun syncDisplayModeButtons() {
        binding.btnViewList.isChecked = viewMode == LibraryViewMode.LIST
        binding.btnViewGrid.isChecked = viewMode == LibraryViewMode.GRID
        binding.btnViewCarousel.isChecked = viewMode == LibraryViewMode.CAROUSEL
    }

    private fun syncDisplayMode() {
        syncDisplayModeButtons()
        val carousel = viewMode == LibraryViewMode.CAROUSEL
        binding.romRecyclerView.visibility = if (carousel) View.GONE else View.VISIBLE
        binding.romCarouselContainer.visibility = if (carousel) View.VISIBLE else View.GONE
        if (carousel) {
            isFabMenuVisible = true
            renderCarousel()
        } else {
            carouselAdapter = null
            binding.romCarousel.adapter = null
            binding.romRecyclerView.layoutManager = createLibraryLayoutManager()
            adapter.setGridMode(viewMode == LibraryViewMode.GRID)
        }
    }

    private fun createLibraryLayoutManager(): RecyclerView.LayoutManager {
        return if (viewMode == LibraryViewMode.GRID) {
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
            notifyLibraryChanged()
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
        notifyLibraryChanged()
        val imm = getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        imm.hideSoftInputFromWindow(binding.searchInput.windowToken, 0)
    }

    private fun notifyLibraryChanged() {
        adapter.notifyDataSetChanged()
        if (viewMode == LibraryViewMode.CAROUSEL) renderCarousel()
        syncEmptyState()
    }

    private fun syncEmptyState() {
        binding.libraryEmptyText.visibility = if (displayList.isEmpty()) View.VISIBLE else View.GONE
    }

    private var searchQuery = ""
    private val displayList get() = if (searchQuery.isBlank()) romList
        else romList.filter {
            it.name.contains(searchQuery, ignoreCase = true) ||
                romDisplayTitle(it).contains(searchQuery, ignoreCase = true)
        }

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
            nameText.text = romDisplayTitle(rom)
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
            nameText.text = romDisplayTitle(rom)
            hdPackIcon.visibility = if (hasInstalledHdPack(rom)) View.VISIBLE else View.GONE
            itemView.setOnClickListener { launchRom(rom) }
            itemView.setOnLongClickListener {
                showGameOptions(rom)
                true
            }
            bindCoverArt(coverImage, rom)
        }
    }

    // ── Carousel ──────────────────────────────────────────────────────────────

    private inner class CarouselRomViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val coverImage: ImageView = itemView.findViewById(R.id.romCoverImage)
        val nameText: TextView = itemView.findViewById(R.id.romGridName)
        private val hdPackIcon: ImageView = itemView.findViewById(R.id.romGridHdPackIcon)
        var romPath: String? = null
        var shade = -1
        private var boundRom: RomEntry? = null
        private var boundIdentified = false

        fun bind(rom: RomEntry) {
            romPath = rom.path
            boundRom = rom
            boundIdentified = isRomIdentified(rom)
            shade = -1
            nameText.text = romDisplayTitle(rom)
            hdPackIcon.visibility = if (hasInstalledHdPack(rom)) View.VISIBLE else View.GONE
            itemView.setOnClickListener { view ->
                // A tap on a side cover brings it to the middle; the middle one launches.
                // A controller press always launches, since focus already scrolled it in.
                if (!view.isInTouchMode || isCarouselChildCentered(view)) {
                    launchRom(rom)
                } else {
                    centerCarouselChild(view)
                }
            }
            itemView.setOnLongClickListener {
                showGameOptions(rom)
                true
            }
            bindCoverArt(coverImage, rom)
        }

        /**
         * Views the carousel keeps cached come back without a rebind, so one bound before
         * its game was identified would keep the placeholder and file-name title.
         */
        fun refreshIfIdentified() {
            val rom = boundRom ?: return
            if (!boundIdentified && isRomIdentified(rom)) bind(rom)
        }
    }

    private fun isLandscape() =
        resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    private fun isRomIdentified(rom: RomEntry) =
        synchronized(coverIdentityLock) { coverTitleByRomPath.containsKey(rom.path) }

    /**
     * Coverflow: one horizontal row snapped to the middle cover, the rest shrinking and
     * darkening with distance. With enough games it loops, by reporting a huge item count
     * and wrapping positions back onto the list.
     */
    private inner class CarouselAdapter(val roms: List<RomEntry>) :
        RecyclerView.Adapter<CarouselRomViewHolder>() {
        val loops = roms.size >= CAROUSEL_MIN_LOOP_SIZE
        var itemWidthPx = 0

        override fun getItemCount() = if (loops) Int.MAX_VALUE else roms.size

        fun romAt(position: Int) = roms[position % roms.size]

        /** The adapter position that shows roms[index], near the middle when looping. */
        fun positionOf(index: Int): Int {
            if (!loops) return index
            val middle = Int.MAX_VALUE / 2
            return middle - middle % roms.size + index
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CarouselRomViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.rom_carousel_item, parent, false)
            view.layoutParams = RecyclerView.LayoutParams(
                itemWidthPx.coerceAtLeast(1),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            val holder = CarouselRomViewHolder(view)
            if (isLandscape()) {
                // Over the faded bottom of the reflection, to leave height for the cover.
                holder.nameText.updateLayoutParams<ConstraintLayout.LayoutParams> {
                    topToBottom = ConstraintLayout.LayoutParams.UNSET
                    bottomToBottom = R.id.romCoverImage
                }
            } else {
                // Two lines always, so the row height does not jump while scrolling.
                holder.nameText.minLines = 2
            }
            // Keep the side tilt from stretching the near edge too much.
            view.cameraDistance = 4000f * resources.displayMetrics.density
            view.setOnFocusChangeListener { focused, hasFocus ->
                if (hasFocus) centerCarouselChild(focused)
            }
            return holder
        }

        override fun onBindViewHolder(holder: CarouselRomViewHolder, position: Int) {
            val lp = holder.itemView.layoutParams
            if (lp.width != itemWidthPx) {
                lp.width = itemWidthPx
                holder.itemView.layoutParams = lp
            }
            holder.bind(romAt(position))
        }

        override fun onViewAttachedToWindow(holder: CarouselRomViewHolder) {
            holder.refreshIfIdentified()
        }
    }

    private fun setupCarousel() {
        carouselLayoutManager = object : LinearLayoutManager(this, HORIZONTAL, false) {
            // Side covers are pulled in toward the middle (to about half their spacing), so
            // ones laid out past the edges are on screen. Lay out at least half the width or
            // two covers' worth beyond each edge, which also starts their art loading early.
            override fun calculateExtraLayoutSpace(
                state: RecyclerView.State,
                extraLayoutSpace: IntArray
            ) {
                val extra = maxOf(2 * (carouselAdapter?.itemWidthPx ?: 0), width / 2)
                extraLayoutSpace[0] = extra
                extraLayoutSpace[1] = extra
            }
        }
        val carousel = binding.romCarousel
        carousel.layoutManager = carouselLayoutManager
        carousel.itemAnimator = null
        carousel.setItemViewCacheSize(8)
        carouselSnapHelper.attachToRecyclerView(carousel)
        carousel.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                applyCarouselTransforms()
            }

            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    rememberCarouselCenter()
                    applyCarouselTransforms()
                }
            }
        })
        // Rebinds (resize, cover art arriving) attach children without a scroll callback.
        carousel.addOnChildAttachStateChangeListener(
            object : RecyclerView.OnChildAttachStateChangeListener {
                override fun onChildViewAttachedToWindow(view: View) {
                    carousel.postOnAnimation { applyCarouselTransforms() }
                }

                override fun onChildViewDetachedFromWindow(view: View) {}
            }
        )
        binding.romCarouselContainer.addOnLayoutChangeListener {
                _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if ((right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) &&
                right > left
            ) {
                carousel.post { sizeCarouselItems() }
            }
        }
    }

    private fun renderCarousel() {
        val roms = displayList.toList()
        if (roms.isEmpty()) {
            carouselAdapter = null
            binding.romCarousel.adapter = null
            return
        }
        val adapter = CarouselAdapter(roms)
        adapter.itemWidthPx = carouselAdapter?.itemWidthPx ?: 0
        carouselAdapter = adapter
        // Attached by sizeCarouselItems once the width is known: unsized items would be
        // 1px wide, and a looping adapter would inflate a thousand of them to fill the row.
        binding.romCarousel.adapter = null
        sizeCarouselItems()
    }

    /**
     * Sizes covers to fit both the width (a few covers across) and the height left under
     * the toolbar, so the enlarged middle cover and its title stay on screen.
     */
    private fun sizeCarouselItems() {
        val adapter = carouselAdapter ?: return
        val carousel = binding.romCarousel
        val container = binding.romCarouselContainer
        val rvWidth = carousel.width
        val availableHeight = container.height - container.paddingTop - container.paddingBottom
        if (rvWidth <= 0 || availableHeight <= 0) return
        val landscape = isLandscape()
        // Portrait: a two-line title and item padding sit under the cover. Landscape is
        // short on height, so its title sits over the reflection and only padding remains.
        val chromeHeight = if (landscape) dp(12) else dp(64)
        val heightShare = if (landscape) 0.92f else 0.85f
        val coverHeightCap = ((availableHeight * heightShare / CAROUSEL_CENTER_SCALE) - chromeHeight) /
            (1f + ReflectedCoverView.REFLECTION_FRACTION)
        val widthFromHeight = (coverHeightCap * COVER_ASPECT).toInt()
        val widthFromWidth = (rvWidth * (if (landscape) 0.26f else 0.55f)).toInt()
        val itemWidth = maxOf(dp(96), minOf(widthFromHeight, widthFromWidth))
        val sidePad = maxOf(0, (rvWidth - itemWidth) / 2)
        // Room for the centered cover to grow past its slot without clipping.
        val itemHeightEstimate =
            (itemWidth / COVER_ASPECT * (1f + ReflectedCoverView.REFLECTION_FRACTION)).toInt() +
                chromeHeight
        val vertPad = dp(8) + (itemHeightEstimate * (CAROUSEL_CENTER_SCALE - 1f) / 2f).toInt()
        carousel.setPadding(sidePad, vertPad, sidePad, vertPad)
        if (itemWidth != adapter.itemWidthPx) {
            adapter.itemWidthPx = itemWidth
            if (carousel.adapter === adapter) adapter.notifyDataSetChanged()
        }
        if (carousel.adapter !== adapter) carousel.adapter = adapter
        scrollCarouselToRemembered()
    }

    private fun scrollCarouselToRemembered() {
        val adapter = carouselAdapter ?: return
        val index = adapter.roms.indexOfFirst { it.path == carouselCenteredPath }.coerceAtLeast(0)
        // The offset is from the start padding, which is what centers an item.
        carouselLayoutManager.scrollToPositionWithOffset(adapter.positionOf(index), 0)
        binding.romCarousel.post { applyCarouselTransforms() }
    }

    private fun rememberCarouselCenter() {
        val adapter = carouselAdapter ?: return
        val snap = carouselSnapHelper.findSnapView(carouselLayoutManager) ?: return
        val position = binding.romCarousel.getChildAdapterPosition(snap)
        if (position == RecyclerView.NO_POSITION) return
        val path = adapter.romAt(position).path
        if (path != carouselCenteredPath) {
            carouselCenteredPath = path
            prefs.edit().putString(PREF_CAROUSEL_ROM_PATH, path).apply()
        }
    }

    private fun notifyCarouselRomChanged(romPath: String) {
        val carousel = binding.romCarousel
        for (i in 0 until carousel.childCount) {
            val holder = carousel.getChildViewHolder(carousel.getChildAt(i))
                as? CarouselRomViewHolder ?: continue
            if (holder.romPath == romPath && holder.bindingAdapterPosition != RecyclerView.NO_POSITION) {
                carouselAdapter?.notifyItemChanged(holder.bindingAdapterPosition)
            }
        }
    }

    private fun isCarouselChildCentered(child: View): Boolean {
        val childCenter = (child.left + child.right) / 2
        return abs(childCenter - binding.romCarousel.width / 2) < child.width / 2
    }

    private fun centerCarouselChild(child: View) {
        val dx = (child.left + child.right) / 2 - binding.romCarousel.width / 2
        if (dx == 0) return
        binding.romCarousel.smoothScrollBy(
            dx, 0, DecelerateInterpolator(1.5f), CAROUSEL_CENTER_SCROLL_MS
        )
    }

    /**
     * Coverflow look, all driven by t, a cover's distance from the middle in slots. The
     * middle cover is enlarged, lifted and flat with its title; the rest shrink, darken,
     * turn toward it and stack up behind it. Every term is smooth in t so nothing steps
     * as covers pass the middle.
     */
    private fun applyCarouselTransforms() {
        val carousel = binding.romCarousel
        val width = carousel.width
        if (width <= 0) return
        val centerX = width / 2f
        val itemWidth = carouselAdapter?.itemWidthPx?.takeIf { it > 0 }?.toFloat() ?: return
        val lift = dp(16).toFloat()
        for (i in 0 until carousel.childCount) {
            val child = carousel.getChildAt(i)
            val holder = carousel.getChildViewHolder(child) as? CarouselRomViewHolder ?: continue
            val dx = (child.left + child.right) / 2f - centerX
            val side = Math.signum(dx)
            val t = abs(dx) / itemWidth
            val near = minOf(1f, t)
            val eased = near * near * (3f - 2f * near)
            val focus = 1f - eased

            val scale = CAROUSEL_SIDE_SCALE + (CAROUSEL_CENTER_SCALE - CAROUSEL_SIDE_SCALE) * focus
            child.scaleX = scale
            child.scaleY = scale
            child.rotationY = -side * CAROUSEL_SIDE_TILT_DEGREES * eased
            // Where the cover is drawn, in slots: the neighbours sit 0.75 out and the rest
            // every 0.5 after, so they overlap like a stack. Its slope is 1 at the middle
            // (the centre cover tracks the finger) and 0.5 from one slot out.
            val drawnAt = 0.5f * t + 0.25f * (1f - (1f - near) * (1f - near))
            child.translationX = side * (drawnAt - t) * itemWidth
            // Strictly falling with distance, so inner covers draw over outer ones.
            child.translationZ = lift / (1f + t)

            holder.nameText.alpha = (1f - minOf(1f, t * 1.5f)).let { it * it }

            val brightness = CAROUSEL_SIDE_BRIGHTNESS + (1f - CAROUSEL_SIDE_BRIGHTNESS) * focus
            val shade = (brightness * 255f).toInt().coerceIn(0, 255)
            if (shade != holder.shade) {
                holder.shade = shade
                if (shade >= 255) {
                    holder.coverImage.clearColorFilter()
                } else {
                    holder.coverImage.setColorFilter(
                        Color.rgb(shade, shade, shade),
                        PorterDuff.Mode.MULTIPLY
                    )
                }
            }
        }
    }

    // ── Per-game options (long press) ─────────────────────────────────────────

    private fun showGameOptions(rom: RomEntry) {
        MaterialAlertDialogBuilder(this)
            .setTitle(romDisplayTitle(rom))
            .setItems(
                arrayOf(
                    "Download HD Pack",
                    "Install HD Pack from ZIP",
                    "Load Save",
                    "Resolution",
                    "Aspect Ratio",
                    "Cheat Manager"
                )
            ) { _, which ->
                when (which) {
                    0 -> startHdPackDownload(rom)
                    1 -> startHdPackInstall(rom)
                    2 -> launchRom(rom, showLoadStateDialog = true)
                    3 -> showPerGameVideoFilterDialog(rom)
                    4 -> showPerGameAspectRatioDialog(rom)
                    5 -> showCheatManager(rom)
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
        pendingHdPackFolderName = hdPackFolderName(rom)
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

    /** The ROM database title once the ROM is identified, else a cleaned-up file name. */
    private fun romDisplayTitle(rom: RomEntry): String {
        val dbTitle = synchronized(coverIdentityLock) { coverTitleByRomPath[rom.path] }
        return CoverNameMatcher.displayTitle(dbTitle ?: rom.name)
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
        val coverMatch = lookupCoverPath(lookupTitle, region) ?: return
        val coverPath = coverMatch.path
        val baseUrl = baseUrlFor(coverMatch.region)
        val cacheFile = coverCacheFile(coverPath, coverMatch.region)
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

    /**
     * Queues [coverView] for the cover at [memoryKey]. Returns true when no load for that
     * cover is running yet, so the caller should start one.
     */
    private fun addCoverWaiter(memoryKey: String, coverView: ImageView, expectedTag: String): Boolean {
        synchronized(coverWaiters) {
            val waiters = coverWaiters[memoryKey]
            if (waiters != null) {
                waiters += coverView to expectedTag
                return false
            }
            coverWaiters[memoryKey] = mutableListOf(coverView to expectedTag)
            return true
        }
    }

    /** Whether any view still waiting on this cover shows its game, i.e. wasn't recycled. */
    private fun isCoverStillWanted(memoryKey: String): Boolean = synchronized(coverWaiters) {
        coverWaiters[memoryKey]?.any { (view, tag) -> view.tag == tag } == true
    }

    /** Hands a finished (or failed, with null) load to every view waiting on it. */
    private fun finishCoverLoad(memoryKey: String, bitmap: Bitmap?) {
        val waiters = synchronized(coverWaiters) { coverWaiters.remove(memoryKey) } ?: return
        if (bitmap == null) return
        runOnUiThread {
            waiters.forEach { (view, tag) -> applyCoverBitmap(view, tag, bitmap) }
        }
    }

    private fun loadCoverFileIntoView(
        coverView: ImageView,
        expectedTag: String,
        file: File,
        memoryKey: String
    ) {
        if (!addCoverWaiter(memoryKey, coverView, expectedTag)) return
        coverDecodeExecutor.execute {
            val bitmap = if (isCoverStillWanted(memoryKey)) {
                BitmapFactory.decodeFile(file.absolutePath)
            } else {
                null
            }
            if (bitmap != null) {
                synchronized(coverMemoryCache) {
                    coverMemoryCache[memoryKey] = bitmap
                }
            }
            finishCoverLoad(memoryKey, bitmap)
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
        if (!addCoverWaiter(memoryKey, coverView, expectedTag)) return

        coverDownloadExecutor.execute {
            var bitmap: Bitmap? = null
            try {
                // Covers flung past while queued are skipped; binding them again re-queues.
                if (!isCoverStillWanted(memoryKey)) return@execute
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
                    return@execute
                }
                if (cacheFile.exists()) cacheFile.delete()
                if (!tempFile.renameTo(cacheFile)) {
                    tempFile.copyTo(cacheFile, overwrite = true)
                    tempFile.delete()
                }
                bitmap = BitmapFactory.decodeFile(cacheFile.absolutePath) ?: return@execute
                synchronized(coverMemoryCache) {
                    coverMemoryCache[memoryKey] = bitmap
                }
            } catch (_: Exception) {
                cacheFile.delete()
            } finally {
                finishCoverLoad(memoryKey, bitmap)
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

        coverIdentifyExecutor.execute {
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
                val index = displayList.indexOfFirst { it.path == rom.path }
                if (index >= 0) {
                    adapter.notifyItemChanged(index)
                }
                notifyCarouselRomChanged(rom.path)
            }
        }
    }

    private fun lookupCoverPath(rawName: String, region: NesRomDatabase.Region): CoverMatch? {
        ensureCoverIndexLoaded()
        if (!coverIndexLoaded) return null

        val candidates = coverLookupCandidates(rawName)
        val missKey = (region.name + ":" + (candidates.firstOrNull().orEmpty()))
        synchronized(coverIndexLock) {
            if (missKey.endsWith(":")) return null
            if (missKey in coverMisses) return null
            val indexes = when (region) {
                NesRomDatabase.Region.FDS -> listOf(
                    NesRomDatabase.Region.FDS to coverIndexFds,
                    NesRomDatabase.Region.JP to coverIndexJp,
                    NesRomDatabase.Region.NA to coverIndex
                )
                NesRomDatabase.Region.JP -> listOf(
                    NesRomDatabase.Region.JP to coverIndexJp,
                    NesRomDatabase.Region.NA to coverIndex,
                    NesRomDatabase.Region.FDS to coverIndexFds
                )
                NesRomDatabase.Region.NA -> listOf(
                    NesRomDatabase.Region.NA to coverIndex,
                    NesRomDatabase.Region.JP to coverIndexJp,
                    NesRomDatabase.Region.FDS to coverIndexFds
                )
            }
            for ((matchedRegion, index) in indexes) {
                for (candidate in candidates) {
                    index[candidate]?.let { return CoverMatch(it, matchedRegion) }
                }
            }
            for ((matchedRegion, index) in indexes) {
                CoverNameMatcher.findContainedTitleMatch(candidates, index)?.let {
                    return CoverMatch(it, matchedRegion)
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
            // Index from local manifests first so covers work offline, then refresh from GitHub.
            applyLocalCoverManifests()
            runOnUiThread {
                notifyLibraryChanged()
            }

            val updated = coverManifestSources.map { (name, baseUrl) ->
                refreshRemoteManifest(name, baseUrl)
            }.any { it }
            if (updated) {
                applyLocalCoverManifests()
                runOnUiThread {
                    notifyLibraryChanged()
                }
            }
        }
    }

    private val coverManifestSources
        get() = listOf(
            "nes_covers" to coverRepoBaseUrl,
            "nes_covers_jp" to coverRepoBaseUrlJp,
            "fds_covers_jp" to coverRepoBaseUrlFds
        )

    private fun applyLocalCoverManifests() {
        val parsedNa = readLocalManifest("nes_covers")
        val parsedJp = readLocalManifest("nes_covers_jp")
        val parsedFds = readLocalManifest("fds_covers_jp")

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
    }

    private fun coverManifestCacheFile(name: String): File {
        return File(File(filesDir, "cover_manifests"), "$name.json")
    }

    /** Prefers the last downloaded manifest, falling back to the copy bundled in assets. */
    private fun readLocalManifest(name: String): Map<String, String> {
        val cacheFile = coverManifestCacheFile(name)
        if (cacheFile.isFile) {
            val cached = try {
                parseCoverManifest(cacheFile.readText())
            } catch (_: Exception) {
                null
            }
            if (!cached.isNullOrEmpty()) return cached
        }
        return try {
            val bundled = assets.open("covers/$name.json").bufferedReader().use { it.readText() }
            parseCoverManifest(bundled)
        } catch (_: Exception) {
            // Covers are optional; library remains usable with placeholders.
            emptyMap()
        }
    }

    /** Returns true when a newer manifest was downloaded and saved. */
    private fun refreshRemoteManifest(name: String, baseUrl: String): Boolean {
        return try {
            val connection = URL(baseUrl + "manifest.json").openConnection().apply {
                connectTimeout = 5000
                readTimeout = 10000
            }
            val manifest = connection.getInputStream().bufferedReader().use { it.readText() }
            if (parseCoverManifest(manifest).isEmpty()) return false

            val cacheFile = coverManifestCacheFile(name)
            if (cacheFile.isFile && cacheFile.readText() == manifest) return false
            cacheFile.parentFile?.mkdirs()
            val tempFile = File(cacheFile.parentFile, "${cacheFile.name}.tmp")
            tempFile.writeText(manifest)
            if (!tempFile.renameTo(cacheFile)) {
                tempFile.copyTo(cacheFile, overwrite = true)
                tempFile.delete()
            }
            true
        } catch (_: Exception) {
            // Offline or GitHub unavailable; keep using the local manifest.
            false
        }
    }

    private fun parseCoverManifest(manifest: String): Map<String, String> {
        val parsed = HashMap<String, String>()
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
        return parsed
    }

    private fun addCoverIndexEntries(out: MutableMap<String, String>, title: String, path: String) {
        for (candidate in coverIndexCandidates(title)) {
            if (candidate.isNotBlank()) out.putIfAbsent(candidate, path)
        }
    }

    private fun coverIndexCandidates(rawName: String): LinkedHashSet<String> {
        return CoverNameMatcher.indexCandidates(rawName)
    }

    private fun coverLookupCandidates(rawName: String): LinkedHashSet<String> {
        return CoverNameMatcher.lookupCandidates(rawName)
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

        binding.drawerSettings.versionValue.text = getString(
            R.string.about_version,
            packageManager.getPackageInfo(packageName, 0).versionName
        )
        binding.drawerSettings.privacyPolicyRow.setOnClickListener {
            openExternalLink(PRIVACY_POLICY_URL)
        }
        binding.drawerSettings.licenseRow.setOnClickListener {
            openExternalLink(LICENSE_URL)
        }
        binding.drawerSettings.nesHdSourceRow.setOnClickListener {
            openExternalLink(NES_HD_REPOSITORY_URL)
        }
        binding.drawerSettings.mesenSourceRow.setOnClickListener {
            openExternalLink(MESEN_REPOSITORY_URL)
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

    private fun openExternalLink(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
        }
        runCatching { startActivity(intent) }
            .onFailure {
                Toast.makeText(this, R.string.unable_to_open_link, Toast.LENGTH_SHORT).show()
            }
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

        binding.drawerSettings.switchAntiFlicker.setOnCheckedChangeListener(null)
        binding.drawerSettings.switchAntiFlicker.isChecked = NativeLib.getSavedAntiFlicker(prefs)
        binding.drawerSettings.switchAntiFlicker.setOnCheckedChangeListener { _, checked ->
            NativeLib.saveAntiFlicker(prefs, checked)
            NativeLib.setAntiFlicker(checked)
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
        if (getDisplayNameForUri(uri).endsWith(".zip", ignoreCase = true)) {
            extractZipRomToCache(uri)?.let { launchRomPath(it.absolutePath) }
            return
        }
        copyRomToCache(uri)?.let { launchRomPath(it.absolutePath) }
    }

    private fun extractZipRomToCache(uri: Uri): File? {
        val dir = File(cacheDir, "roms/${Integer.toHexString(uri.toString().hashCode())}")
        dir.deleteRecursively()
        val rom = try {
            ZipRomExtractor.extract(this, uri, dir, ::isSupportedRomName).firstOrNull()
        } catch (_: Exception) {
            null
        }
        if (rom == null) {
            Toast.makeText(this, "No NES ROM found in this zip", Toast.LENGTH_LONG).show()
        }
        return rom
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

    /** Rescans the last picked ROM folder without asking for it again. */
    private fun refreshLibrary() {
        val folder = savedRomFolderUri()
        if (folder == null) {
            Toast.makeText(this, "Pick your ROM folder first", Toast.LENGTH_SHORT).show()
            pickFolderLauncher.launch(null)
            return
        }
        scanFolder(folder)
    }

    private fun savedRomFolderUri(): Uri? {
        val granted = contentResolver.persistedUriPermissions
            .filter { it.isReadPermission && DocumentsContract.isTreeUri(it.uri) }
        prefs.getString(PREF_ROM_FOLDER_URI, null)?.let { saved ->
            granted.firstOrNull { it.uri.toString() == saved }?.let { return it.uri }
        }
        // Folders picked before the folder was remembered still hold a persisted grant.
        return granted.maxByOrNull { it.persistedTime }?.uri
    }

    private fun scanFolder(uri: Uri) {
        val tree = DocumentFile.fromTreeUri(this, uri) ?: return
        Toast.makeText(this, "Scanning ROM folder…", Toast.LENGTH_SHORT).show()

        thread(name = "RomFolderScan") {
            val found = mutableListOf<RomEntry>()
            val extractedDirs = mutableSetOf<String>()

            fun scanRecursive(dir: DocumentFile) {
                for (file in dir.listFiles()) {
                    if (file.isDirectory) scanRecursive(file)
                    else {
                        val n = file.name ?: continue
                        if (isSupportedRomName(n)) {
                            found.add(RomEntry(n, file.uri.toString()))
                        } else if (n.endsWith(".zip", ignoreCase = true)) {
                            // Zips without a ROM inside are simply skipped, as are unreadable ones.
                            val result = runCatching {
                                ZipRomExtractor.extractCached(this, file, ::isSupportedRomName)
                            }.getOrNull() ?: continue
                            extractedDirs += result.dirName
                            result.roms.forEach { found.add(RomEntry(it.name, it.absolutePath)) }
                        }
                    }
                }
            }

            val error = try {
                if (!tree.canRead()) throw IOException("Folder is no longer accessible")
                scanRecursive(tree)
                ZipRomExtractor.prune(this, extractedDirs)
                null
            } catch (ex: Exception) {
                ex.message ?: "Could not read folder"
            }

            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (error != null) {
                    Toast.makeText(this, "$error. Pick the ROM folder again.", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                found.sortBy { it.name }
                romList.clear()
                romList.addAll(found)
                notifyLibraryChanged()
                saveRomList()
                Toast.makeText(this, "Found ${romList.size} ROMs", Toast.LENGTH_SHORT).show()
            }
        }
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

        // Some packs ship optional variants in subfolders (e.g. Customization/*/hires.txt),
        // so the hires.txt closest to the zip root wins.
        contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input).use { zip ->
                var entry: ZipEntry? = zip.nextEntry
                while (entry != null) {
                    val n = normalizeZipPath(entry.name)
                    if (!entry.isDirectory &&
                        (n.equals("hires.txt", ignoreCase = true) ||
                                n.endsWith("/hires.txt", ignoreCase = true))) {
                        val current = hiresPath
                        if (current == null || n.count { it == '/' } < current.count { it == '/' }) {
                            hiresPath = n
                        }
                        if (!n.contains('/')) break
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

    private fun importHdPack(
        uri: Uri,
        info: HdPackZipInfo,
        targetName: String,
        overwrite: Boolean,
        onFinished: (() -> Unit)? = null
    ) {
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
            onFinished?.invoke()

            runOnUiThread {
                notifyLibraryChanged()
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    // ── HD Pack download ──────────────────────────────────────────────────────

    private fun startHdPackDownload(rom: RomEntry) {
        val folderName = hdPackFolderName(rom)
        val loading = showProgressDialog("Download HD Pack", "Loading HD pack list…", cancellable = false)

        thread(name = "HdPackCatalog") {
            val result = runCatching {
                val packs = HdPackCatalog.load(this)
                val romSha1 = HdPackCatalog.romFileSha1(this, rom.path)
                HdPackCatalog.rank(packs, rom.name, romSha1)
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                loading.dialog.dismiss()
                result.onSuccess { ranked ->
                    if (ranked.isEmpty()) {
                        Toast.makeText(this, "No HD packs available", Toast.LENGTH_LONG).show()
                    } else {
                        showHdPackChooser(rom, folderName, ranked)
                    }
                }.onFailure { ex ->
                    Toast.makeText(this, ex.message ?: "Could not load HD pack list", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showHdPackChooser(
        rom: RomEntry,
        folderName: String,
        ranked: List<Pair<HdPackCatalog.Pack, HdPackCatalog.Match>>
    ) {
        val hasMatches = ranked.any { it.second != HdPackCatalog.Match.NONE }
        var showAll = !hasMatches
        if (!hasMatches) {
            Toast.makeText(this, "No pack matched this ROM; showing all packs", Toast.LENGTH_SHORT).show()
        }

        fun show() {
            val visible = if (showAll) ranked else ranked.filter { it.second != HdPackCatalog.Match.NONE }
            val labels = visible.map { (pack, match) ->
                val tag = when (match) {
                    HdPackCatalog.Match.ROM_HASH -> " · made for your ROM"
                    HdPackCatalog.Match.TITLE -> " · title match"
                    HdPackCatalog.Match.NONE -> ""
                }
                "${pack.title}\n${formatSize(pack.size)}$tag"
            }.toTypedArray()

            val builder = MaterialAlertDialogBuilder(this)
                .setTitle(if (showAll) "All HD Packs" else "HD Packs for ${romDisplayTitle(rom)}")
                .setItems(labels) { _, which ->
                    val (pack, match) = visible[which]
                    confirmHdPackDownload(pack, match, folderName)
                }
                .setNegativeButton("Cancel", null)
            if (!showAll) {
                builder.setNeutralButton("Show all") { _, _ ->
                    showAll = true
                    show()
                }
            }
            builder.show()
        }
        show()
    }

    private fun confirmHdPackDownload(
        pack: HdPackCatalog.Pack,
        match: HdPackCatalog.Match,
        folderName: String
    ) {
        val lines = mutableListOf("Download ${formatSize(pack.size)} and install it for “$folderName”?")
        if (pack.notes.isNotBlank()) lines += pack.notes
        when (match) {
            HdPackCatalog.Match.ROM_HASH -> Unit
            HdPackCatalog.Match.TITLE ->
                lines += "This pack may have been made for a different revision of the game."
            HdPackCatalog.Match.NONE ->
                lines += "This pack doesn't appear to be for this game and may not work."
        }
        if (AppStorage.hdPackDir(this, folderName).exists()) {
            lines += "The HD pack currently installed for this game will be replaced."
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(pack.title)
            .setMessage(lines.joinToString("\n\n"))
            .setPositiveButton("Download") { _, _ -> downloadAndInstallHdPack(pack, folderName) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun downloadAndInstallHdPack(pack: HdPackCatalog.Pack, folderName: String) {
        val zipFile = File(File(externalCacheDir ?: cacheDir, "hdpack_downloads"), "${pack.id}.zip")
        val cancelled = AtomicBoolean(false)
        val progress = showProgressDialog(pack.title, "Starting download…", cancellable = true) {
            cancelled.set(true)
        }

        thread(name = "HdPackDownload") {
            val error = try {
                HdPackCatalog.download(pack, zipFile, { cancelled.get() }) { downloaded, total ->
                    runOnUiThread {
                        if (total > 0) {
                            progress.bar.isIndeterminate = false
                            progress.bar.setProgressCompat((downloaded * 100 / total).toInt(), true)
                        }
                        progress.text.text = "${formatSize(downloaded)} of ${formatSize(total)}"
                    }
                }
                null
            } catch (_: HdPackCatalog.CancelledException) {
                ""
            } catch (ex: Exception) {
                ex.message ?: "Download failed"
            }

            runOnUiThread {
                if (!isDestroyed) progress.dialog.dismiss()
                if (error != null) {
                    zipFile.delete()
                    if (error.isNotEmpty()) {
                        Toast.makeText(this, "Could not download HD pack: $error", Toast.LENGTH_LONG).show()
                    }
                    return@runOnUiThread
                }
                installDownloadedHdPack(zipFile, folderName)
            }
        }
    }

    private fun installDownloadedHdPack(zipFile: File, folderName: String) {
        val uri = Uri.fromFile(zipFile)
        val info = try {
            inspectHdPackZip(uri)
        } catch (_: IOException) {
            null
        }
        if (info == null) {
            zipFile.delete()
            Toast.makeText(this, "Downloaded file is not a valid HD pack", Toast.LENGTH_LONG).show()
            return
        }
        importHdPack(uri, info, folderName, overwrite = true) { zipFile.delete() }
    }

    private class ProgressDialog(
        val dialog: androidx.appcompat.app.AlertDialog,
        val text: TextView,
        val bar: LinearProgressIndicator
    )

    private fun showProgressDialog(
        title: String,
        message: String,
        cancellable: Boolean,
        onCancel: (() -> Unit)? = null
    ): ProgressDialog {
        val text = TextView(this).apply {
            this.text = message
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        }
        val bar = LinearProgressIndicator(this).apply {
            isIndeterminate = true
            max = 100
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(8))
            addView(text)
            addView(bar, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(16) })
        }
        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(content)
            .setCancelable(false)
        if (cancellable) builder.setNegativeButton("Cancel") { _, _ -> onCancel?.invoke() }
        return ProgressDialog(builder.show(), text, bar)
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1_000_000 -> String.format(Locale.US, "%.1f MB", bytes / 1_000_000.0)
        bytes >= 1_000 -> String.format(Locale.US, "%d KB", bytes / 1_000)
        else -> "$bytes B"
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

    /**
     * The core loads HdPacks/<launched file name without extension>/hires.txt, and launched
     * files are always named with [sanitizeFileName], so the folder must use the same rule.
     */
    private fun hdPackFolderName(rom: RomEntry) =
        romNameWithoutExtension(sanitizeFileName(rom.name))

    private fun hasInstalledHdPack(rom: RomEntry): Boolean {
        val folderName = hdPackFolderName(rom)
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

    /** Runs the most recent task first, so what's on screen after a fling loads first. */
    private fun newestFirstExecutor(threads: Int) = ThreadPoolExecutor(
        threads, threads, 30L, TimeUnit.SECONDS,
        object : LinkedBlockingDeque<Runnable>() {
            override fun offer(e: Runnable) = offerFirst(e)
        }
    ).apply { allowCoreThreadTimeOut(true) }

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
            NativeLib.getSavedAspectRatio(prefs),
            NativeLib.getSavedAntiFlicker(prefs)
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

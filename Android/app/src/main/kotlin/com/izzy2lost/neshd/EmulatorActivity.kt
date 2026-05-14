package com.izzy2lost.neshd

import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.izzy2lost.neshd.databinding.ActivityEmulatorBinding
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

class EmulatorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEmulatorBinding
    private var emulatorStopped = false
    private var touchControlsVisible = true
    private var dialogPauseDepth = 0
    private var isFdsRom = false

    companion object {
        const val EXTRA_ROM_PATH = "ROM_PATH"
        const val EXTRA_LOAD_STATE_SLOT = "LOAD_STATE_SLOT"
        const val EXTRA_SHOW_LOAD_STATE_DIALOG = "SHOW_LOAD_STATE_DIALOG"
        const val EXTRA_VIDEO_FILTER_TYPE = "VIDEO_FILTER_TYPE"
        const val EXTRA_ASPECT_RATIO = "ASPECT_RATIO"
    }

    private enum class SaveStateDialogMode {
        SAVE,
        LOAD
    }

    private enum class MenuButtonTone {
        PRIMARY,
        SECONDARY,
        DANGER
    }

    // Mapping from Android KeyEvent keycodes to Mesen NES button IDs
    private val keyMap = mapOf(
        KeyEvent.KEYCODE_BUTTON_A      to NativeLib.BTN_A,
        KeyEvent.KEYCODE_BUTTON_B      to NativeLib.BTN_B,
        KeyEvent.KEYCODE_BUTTON_X      to NativeLib.BTN_B,
        KeyEvent.KEYCODE_BUTTON_START  to NativeLib.BTN_START,
        KeyEvent.KEYCODE_BUTTON_SELECT to NativeLib.BTN_SELECT,
        KeyEvent.KEYCODE_BUTTON_MODE   to NativeLib.BTN_SELECT,
        KeyEvent.KEYCODE_DPAD_UP       to NativeLib.BTN_UP,
        KeyEvent.KEYCODE_DPAD_DOWN     to NativeLib.BTN_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT     to NativeLib.BTN_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT    to NativeLib.BTN_RIGHT,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEmulatorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupOverlayButtons()

        hideSystemUi()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                showGameMenu()
            }
        })

        val romPath = intent.getStringExtra(EXTRA_ROM_PATH) ?: run {
            finish()
            return
        }
        isFdsRom = isFdsPath(romPath)
        val loadStateSlot = intent.getIntExtra(EXTRA_LOAD_STATE_SLOT, 0)
            .takeIf { it in 1..10 }
        val showLoadStateDialog = intent.getBooleanExtra(EXTRA_SHOW_LOAD_STATE_DIALOG, false)
        val videoFilterOverride = intent.optionalIntExtra(EXTRA_VIDEO_FILTER_TYPE)
        val aspectRatioOverride = intent.optionalIntExtra(EXTRA_ASPECT_RATIO)

        initializeNativeIfNeeded()
        applySavedNativeSettings(videoFilterOverride, aspectRatioOverride)

        if (!NativeLib.loadRom(romPath)) {
            val lastLogLine = NativeLib.getLog()
                .lineSequence()
                .filter { it.isNotBlank() }
                .lastOrNull()
            val message = lastLogLine ?: "Could not load ROM"
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        applyEnabledCheats()
        loadStateSlot?.let { slot ->
            val ok = NativeLib.loadState(slot)
            Toast.makeText(
                this,
                if (ok) "State $slot loaded" else "Could not load state $slot",
                Toast.LENGTH_SHORT
            ).show()
        }
        applySavedNativeSettings(videoFilterOverride, aspectRatioOverride)
        if (showLoadStateDialog && loadStateSlot == null) {
            binding.root.post {
                if (!emulatorStopped && !isFinishing && !isDestroyed) {
                    showSaveStateDialog(SaveStateDialogMode.LOAD)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemUi()
        binding.glSurface.onResume()
        if (dialogPauseDepth > 0) {
            NativeLib.pause()
        } else {
            NativeLib.resume()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        hideSystemUi()
    }

    override fun onPause() {
        super.onPause()
        NativeLib.pause()
        NativeLib.flushBatterySave()
        binding.glSurface.onPause()
    }

    override fun onStop() {
        super.onStop()
        if (isFinishing) {
            stopEmulator()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopEmulator()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        keyMap[keyCode]?.let { btn ->
            NativeLib.setButtonState(btn, true)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        keyMap[keyCode]?.let { btn ->
            NativeLib.setButtonState(btn, false)
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun setupOverlayButtons() {
        binding.gameMenuButton.setOnClickListener {
            showGameMenu()
        }
        binding.touchVisibilityButton.setOnClickListener {
            setTouchControlsVisible(!touchControlsVisible)
        }
        setTouchControlsVisible(touchControlsVisible)
    }

    private fun setTouchControlsVisible(visible: Boolean) {
        touchControlsVisible = visible
        if (!visible) {
            binding.touchController.releaseAllButtons()
        }

        binding.touchController.visibility = if (visible) View.VISIBLE else View.GONE
        binding.touchVisibilityButton.setImageResource(
            if (visible) R.drawable.visibility_off_24px else R.drawable.visibility_24px
        )
        binding.touchVisibilityButton.contentDescription =
            if (visible) "Hide touch controls" else "Show touch controls"
    }

    private fun showGameMenu() {
        if (emulatorStopped) return

        pauseForDialog()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.dp, 12.dp, 24.dp, 4.dp)
        }
        val scrollView = ScrollView(this).apply {
            isFillViewport = false
            addView(
                content,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        val saveButton = buildMenuButton("Save state", R.drawable.ic_save_24, MenuButtonTone.PRIMARY)
        val loadButton = buildMenuButton("Load state", R.drawable.ic_restore_24, MenuButtonTone.PRIMARY)
        val switchFdsSideButton = if (isFdsRom) {
            buildMenuButton("Switch FDS disk side", R.drawable.ic_swap_vert_24, MenuButtonTone.SECONDARY)
        } else {
            null
        }
        val nextFdsDiskButton = if (isFdsRom) {
            buildMenuButton("Next FDS disk", R.drawable.ic_skip_next_24, MenuButtonTone.SECONDARY)
        } else {
            null
        }
        val exitButton = buildMenuButton("Exit to game list", R.drawable.ic_exit_to_app_24, MenuButtonTone.DANGER)
        content.addView(saveButton)
        content.addView(loadButton)
        switchFdsSideButton?.let(content::addView)
        nextFdsDiskButton?.let(content::addView)
        content.addView(exitButton)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Game menu")
            .setView(scrollView)
            .create()

        saveButton.setOnClickListener {
            dialog.dismiss()
            showSaveStateDialog(SaveStateDialogMode.SAVE)
        }
        loadButton.setOnClickListener {
            dialog.dismiss()
            showSaveStateDialog(SaveStateDialogMode.LOAD)
        }
        switchFdsSideButton?.setOnClickListener {
            NativeLib.switchFdsDiskSide()
            Toast.makeText(this, "Switching FDS disk side", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }
        nextFdsDiskButton?.setOnClickListener {
            NativeLib.insertNextFdsDisk()
            Toast.makeText(this, "Inserting next FDS disk", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }
        exitButton.setOnClickListener {
            dialog.dismiss()
            exitToGameList()
        }

        dialog.setOnDismissListener {
            resumeAfterDialog()
        }
        dialog.setOnShowListener {
            hideSystemUi()
        }
        dialog.show()
    }

    private fun showSaveStateDialog(mode: SaveStateDialogMode) {
        if (emulatorStopped) return

        pauseForDialog()
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 8.dp, 0, 8.dp)
        }
        val scrollView = ScrollView(this).apply {
            addView(
                list,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        lateinit var dialog: AlertDialog
        val onSlotAction: (Int) -> Unit = { slot ->
            val ok = when (mode) {
                SaveStateDialogMode.SAVE -> NativeLib.saveState(slot)
                SaveStateDialogMode.LOAD -> NativeLib.loadState(slot)
            }
            val verb = if (mode == SaveStateDialogMode.SAVE) "saved" else "loaded"
            val action = if (mode == SaveStateDialogMode.SAVE) "save" else "load"
            Toast.makeText(
                this,
                if (ok) "State $slot $verb" else "Could not $action state $slot",
                Toast.LENGTH_SHORT
            ).show()
            if (ok) {
                dialog.dismiss()
            }
        }

        for (slot in 1..10) {
            addSaveStateRow(list, slot, mode, onSlotAction)
        }

        val title = if (mode == SaveStateDialogMode.SAVE) "Save state" else "Load state"
        dialog = MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(scrollView)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnDismissListener {
            resumeAfterDialog()
        }
        dialog.setOnShowListener {
            hideSystemUi()
        }
        dialog.show()
    }

    private fun addSaveStateRow(
        parent: LinearLayout,
        slot: Int,
        mode: SaveStateDialogMode,
        onSlotAction: (Int) -> Unit
    ) {
        val stateFile = NativeLib.getSaveStatePath(slot).takeIf { it.isNotBlank() }?.let { File(it) }
        val stateExists = stateFile?.isFile == true
        val preview = if (stateExists) loadSaveStatePreview(slot) else null
        val rowPaddingHorizontal = 20.dp
        val rowPaddingVertical = 10.dp

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(rowPaddingHorizontal, rowPaddingVertical, rowPaddingHorizontal, rowPaddingVertical)
            setBackgroundResource(selectableItemBackground())
            isClickable = true
        }

        val imageView = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(96.dp, 72.dp)
            setBackgroundColor(Color.rgb(18, 18, 18))
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = if (stateExists) "Preview for save state $slot" else "Empty save state $slot"
            if (preview != null) {
                setImageBitmap(preview)
                setOnClickListener {
                    showSaveStatePreviewDialog(slot, preview)
                }
            }
        }
        row.addView(imageView)

        val textColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(14.dp, 0, 12.dp, 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        textColumn.addView(TextView(this).apply {
            text = "Slot $slot"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
        })
        textColumn.addView(TextView(this).apply {
            text = if (stateExists) {
                "Saved ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(stateFile.lastModified()))}"
            } else {
                "Empty"
            }
            textSize = 13f
            alpha = 0.72f
        })
        row.addView(textColumn)

        val actionText = if (mode == SaveStateDialogMode.SAVE) "Save" else "Load"
        val actionButton = Button(this).apply {
            text = actionText
            isAllCaps = false
            isEnabled = mode == SaveStateDialogMode.SAVE || stateExists
            minWidth = 80.dp
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        row.addView(actionButton)

        val action = View.OnClickListener {
            if (mode == SaveStateDialogMode.LOAD && !stateExists) {
                Toast.makeText(this, "Slot $slot is empty", Toast.LENGTH_SHORT).show()
            } else {
                onSlotAction(slot)
            }
        }
        row.setOnClickListener(action)
        actionButton.setOnClickListener(action)
        parent.addView(row)
    }

    private fun showSaveStatePreviewDialog(slot: Int, bitmap: Bitmap) {
        pauseForDialog()
        val imageView = ImageView(this).apply {
            adjustViewBounds = true
            setImageBitmap(bitmap)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(16.dp, 8.dp, 16.dp, 8.dp)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Slot $slot preview")
            .setView(imageView)
            .setPositiveButton("Close", null)
            .create()
            .also { dialog ->
                dialog.setOnDismissListener {
                    resumeAfterDialog()
                }
                dialog.setOnShowListener {
                    hideSystemUi()
                }
                dialog.show()
            }
    }

    private fun loadSaveStatePreview(slot: Int): Bitmap? {
        val bytes = NativeLib.getSaveStatePreview(slot) ?: return null
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    private fun buildMenuButton(
        label: String,
        iconResId: Int,
        tone: MenuButtonTone
    ): MaterialButton {
        val containerColor = when (tone) {
            MenuButtonTone.PRIMARY -> themeColor(
                com.google.android.material.R.attr.colorPrimaryContainer,
                Color.rgb(255, 218, 214)
            )
            MenuButtonTone.SECONDARY -> getColor(R.color.brand_near_black)
            MenuButtonTone.DANGER -> themeColor(
                com.google.android.material.R.attr.colorErrorContainer,
                Color.rgb(249, 222, 220)
            )
        }
        val contentColor = when (tone) {
            MenuButtonTone.PRIMARY -> themeColor(
                com.google.android.material.R.attr.colorOnPrimaryContainer,
                Color.rgb(65, 0, 2)
            )
            MenuButtonTone.SECONDARY -> getColor(R.color.brand_white)
            MenuButtonTone.DANGER -> themeColor(
                com.google.android.material.R.attr.colorOnErrorContainer,
                Color.rgb(65, 14, 11)
            )
        }
        val outlineColor = themeColor(com.google.android.material.R.attr.colorOutlineVariant, Color.rgb(216, 194, 191))

        return MaterialButton(this).apply {
            text = label
            isAllCaps = false
            minHeight = 56.dp
            cornerRadius = 16.dp
            gravity = Gravity.CENTER_VERTICAL
            setPadding(18.dp, 0, 18.dp, 0)
            setTextColor(contentColor)
            setIconResource(iconResId)
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
            iconPadding = 12.dp
            iconTint = ColorStateList.valueOf(contentColor)
            backgroundTintList = ColorStateList.valueOf(containerColor)
            strokeColor = ColorStateList.valueOf(outlineColor)
            strokeWidth = 1.dp
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                56.dp
            ).apply {
                bottomMargin = 8.dp
            }
        }
    }

    private fun themeColor(attr: Int, fallback: Int): Int {
        val outValue = TypedValue()
        if (!theme.resolveAttribute(attr, outValue, true)) return fallback
        return if (outValue.resourceId != 0) getColor(outValue.resourceId) else outValue.data
    }

    private fun isFdsPath(path: String): Boolean {
        val lower = path.substringBefore('?').lowercase()
        return lower.endsWith(".fds") || lower.endsWith(".qd")
    }

    private fun pauseForDialog() {
        dialogPauseDepth++
        if (!emulatorStopped) {
            NativeLib.pause()
        }
    }

    private fun resumeAfterDialog() {
        dialogPauseDepth = (dialogPauseDepth - 1).coerceAtLeast(0)
        if (dialogPauseDepth == 0 && !emulatorStopped && !isFinishing && !isDestroyed) {
            NativeLib.resume()
            hideSystemUi()
        }
    }

    private fun exitToGameList() {
        stopEmulator()
        finish()
    }

    private fun selectableItemBackground(): Int {
        val outValue = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
        return outValue.resourceId
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).roundToInt()

    private fun hideSystemUi() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }

    private fun initializeNativeIfNeeded() {
        val homeDir = AppStorage.nesHdHomeDir(this)
        NativeLib.initialize(homeDir.absolutePath)
    }

    private fun applySavedNativeSettings(
        videoFilterOverride: Int? = null,
        aspectRatioOverride: Int? = null
    ) {
        val prefs = getSharedPreferences(NativeLib.PREFS_NAME, MODE_PRIVATE)
        NativeLib.applyVideoSettings(
            videoFilterOverride ?: NativeLib.getSavedVideoFilter(prefs),
            prefs.getBoolean(NativeLib.PREF_HD_PACKS, true),
            aspectRatioOverride ?: NativeLib.getSavedAspectRatio(prefs)
        )
    }

    private fun android.content.Intent.optionalIntExtra(name: String): Int? =
        if (hasExtra(name)) getIntExtra(name, 0) else null

    private fun applyEnabledCheats() {
        val sha1 = NativeLib.getRomCheatHash()
        if (sha1.isBlank()) {
            NativeLib.clearCheats()
            return
        }

        val codes = CheatDatabase.enabledCodeParts(this, sha1)
        if (codes.isEmpty()) {
            NativeLib.clearCheats()
        } else {
            NativeLib.setCheats(codes)
        }
    }

    private fun stopEmulator() {
        if (emulatorStopped) return
        NativeLib.flushBatterySave()
        NativeLib.stopRom()
        emulatorStopped = true
    }
}

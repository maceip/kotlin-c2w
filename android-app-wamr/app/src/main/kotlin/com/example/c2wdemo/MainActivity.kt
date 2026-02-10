package com.example.c2wdemo

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.example.c2wdemo.data.FriscyPreferences
import com.example.c2wdemo.data.ImageManager
import com.example.c2wdemo.gauge.GaugeData
import com.example.c2wdemo.gauge.SystemStatsProvider
import com.example.c2wdemo.gauge.ZimStatusGauge
import com.example.c2wdemo.ime.ControlFocusInsetsAnimationCallback
import com.example.c2wdemo.ime.RootViewDeferringInsetsCallback
import com.example.c2wdemo.ime.TranslateDeferringInsetsAnimationCallback
import com.example.c2wdemo.ui.OnboardingScreen
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import com.termux.view.WasmTerminalBridge
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var terminalView: TerminalView
    private lateinit var contentRoot: View
    private lateinit var zimHelperBar: LinearLayout
    private lateinit var topAppBar: LinearLayout
    private lateinit var tvImageName: TextView
    private lateinit var btnCtrl: TextView
    private lateinit var btnPrevWord: TextView
    private lateinit var btnNextWord: TextView
    private lateinit var btnUp: TextView
    private lateinit var btnDown: TextView
    private lateinit var btnTab: TextView
    private lateinit var btnEsc: TextView
    private lateinit var btnSnapshot: ImageButton
    private lateinit var btnExport: ImageButton
    private lateinit var btnImport: ImageButton
    private lateinit var btnSwitch: ImageButton
    private lateinit var statsProvider: SystemStatsProvider
    private val gaugeState = mutableStateOf(GaugeData())

    /** Sticky Ctrl mode: next character typed is sent as a control character. */
    private var ctrlSticky = false

    /** The bridge between WamrRuntime and the terminal emulator. */
    private lateinit var bridge: WasmTerminalBridge

    /** Bound VmService reference. */
    private var vmService: VmService? = null
    private var serviceBound = false

    /** Preferences and image management. */
    private lateinit var friscyPreferences: FriscyPreferences
    private lateinit var imageManager: ImageManager

    /** Current image ID. */
    private var currentImageId: String? = null

    /** Zim color palette for the terminal emulator. */
    companion object {
        // Zim theme color indices (standard ANSI 0-15)
        private val ZIM_PALETTE = intArrayOf(
            0xFF1A1A2E.toInt(), // 0 black
            0xFFFF1493.toInt(), // 1 red -> zim pink
            0xFF00FF88.toInt(), // 2 green -> zim green
            0xFFC77DFF.toInt(), // 3 yellow -> zim purple highlight
            0xFF00FFFF.toInt(), // 4 blue -> zim cyan
            0xFF9B4DCA.toInt(), // 5 magenta -> zim purple light
            0xFF00CED1.toInt(), // 6 cyan
            0xFFCCCCCC.toInt(), // 7 white
            0xFF333355.toInt(), // 8 bright black
            0xFFFF69B4.toInt(), // 9 bright red
            0xFF33FF99.toInt(), // 10 bright green
            0xFFD9A0FF.toInt(), // 11 bright yellow
            0xFF66FFFF.toInt(), // 12 bright blue
            0xFFBB77DD.toInt(), // 13 bright magenta
            0xFF33E0E0.toInt(), // 14 bright cyan
            0xFFFFFFFF.toInt(), // 15 bright white
        )
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as VmService.LocalBinder
            vmService = localBinder.service
            serviceBound = true

            // Replay buffered output from service (output produced while UI was away)
            val buffered = localBinder.service.getBufferedOutput()
            if (buffered.isNotEmpty()) {
                bridge.processOutput(buffered)
                terminalView.onScreenUpdated()
            }

            // Register for live output
            localBinder.service.setOutputCallback { text ->
                runOnUiThread {
                    bridge.processOutput(text)
                    terminalView.onScreenUpdated()
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            vmService = null
            serviceBound = false
        }
    }

    // File picker for exporting checkpoint
    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri != null && currentImageId != null) {
            contentResolver.openOutputStream(uri)?.use { outputStream ->
                val success = imageManager.exportCheckpoint(currentImageId!!, outputStream)
                val message = if (success) "Checkpoint exported" else "Export failed"
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // File picker for importing checkpoint
    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null && currentImageId != null) {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                val success = imageManager.importCheckpoint(currentImageId!!, inputStream)
                val message = if (success) "Checkpoint imported - restart to apply" else "Import failed"
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Edge-to-edge: let the app handle window insets
        WindowCompat.setDecorFitsSystemWindows(window, false)

        super.onCreate(savedInstanceState)

        friscyPreferences = FriscyPreferences(this)
        imageManager = ImageManager(this)

        // Show onboarding immediately while we check preferences
        // This prevents a blank screen during DataStore read
        showOnboardingScreen()

        // Check if we should go directly to terminal
        lifecycleScope.launch {
            val onboardingDone = friscyPreferences.onboardingCompleted.first()
            val selectedImage = friscyPreferences.selectedImage.first()

            if (onboardingDone && selectedImage != null) {
                currentImageId = selectedImage
                initializeTerminalUI()
                startVmService(selectedImage)
            }
            // Otherwise, onboarding screen is already showing
        }
    }

    private fun showOnboardingScreen() {
        setContent {
            OnboardingScreen(
                onImageSelected = { imageId ->
                    lifecycleScope.launch {
                        friscyPreferences.setSelectedImage(imageId)
                        friscyPreferences.setOnboardingCompleted(true)
                        currentImageId = imageId
                        initializeTerminalUI()
                        startVmService(imageId)
                    }
                }
            )
        }
    }

    private fun initializeTerminalUI() {
        setContentView(R.layout.activity_main)

        // Immersive mode: hide status bar and navigation bar
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.statusBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        terminalView = findViewById(R.id.terminalView)
        contentRoot = findViewById(R.id.contentRoot)
        zimHelperBar = findViewById(R.id.zimHelperBar)
        topAppBar = findViewById(R.id.topAppBar)
        tvImageName = findViewById(R.id.tvImageName)
        btnCtrl = findViewById(R.id.btnCtrl)
        btnPrevWord = findViewById(R.id.btnPrevWord)
        btnNextWord = findViewById(R.id.btnNextWord)
        btnUp = findViewById(R.id.btnUp)
        btnDown = findViewById(R.id.btnDown)
        btnTab = findViewById(R.id.btnTab)
        btnEsc = findViewById(R.id.btnEsc)
        btnSnapshot = findViewById(R.id.btnSnapshot)
        btnExport = findViewById(R.id.btnExport)
        btnImport = findViewById(R.id.btnImport)
        btnSwitch = findViewById(R.id.btnSwitch)

        // Update image name in top bar
        updateImageName()

        // Initialize the bridge
        bridge = WasmTerminalBridge()
        bridge.init(
            { input -> WamrRuntime.sendInput(input) },
            { WamrRuntime.isRunning }
        )
        bridge.setBridgeListener(bridgeListener)

        // Attach bridge to terminal view (this triggers updateSize -> initializeEmulator)
        terminalView.setTerminalViewClient(terminalViewClient)
        terminalView.setTerminalSessionClient(terminalSessionClient)
        terminalView.attachBridge(bridge)

        // Set a reasonable text size
        terminalView.setTextSize(
            (14 * resources.displayMetrics.density).toInt()
        )

        // Enable cursor blinker
        terminalView.setTerminalCursorBlinkerRate(500)
        terminalView.setTerminalCursorBlinkerState(true, false)

        setupImeAnimation()
        setupZimHelperBar()
        setupTopBarActions()
        setupStatusGauge()
    }

    private fun updateImageName() {
        val imageInfo = currentImageId?.let { ImageManager.getImageInfo(it) }
        tvImageName.text = imageInfo?.displayName?.uppercase() ?: "FRISCY"
    }

    private fun startVmService(imageId: String) {
        val serviceIntent = Intent(this, VmService::class.java).apply {
            putExtra(VmService.EXTRA_IMAGE_ID, imageId)
        }
        startService(serviceIntent)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun setupTopBarActions() {
        btnSnapshot.setOnClickListener {
            val success = vmService?.saveCheckpoint() ?: false
            val message = if (success) "Checkpoint saved" else "No VM running"
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }

        btnExport.setOnClickListener {
            val imageId = currentImageId ?: return@setOnClickListener
            if (imageManager.hasCheckpoint(imageId)) {
                exportLauncher.launch("${imageId}_checkpoint.snap")
            } else {
                Toast.makeText(this, "No checkpoint to export", Toast.LENGTH_SHORT).show()
            }
        }

        btnImport.setOnClickListener {
            importLauncher.launch(arrayOf("application/octet-stream", "*/*"))
        }

        btnSwitch.setOnClickListener {
            // Save current checkpoint before switching
            vmService?.saveCheckpoint()

            // Unbind from service
            if (serviceBound) {
                vmService?.clearOutputCallback()
                unbindService(serviceConnection)
                serviceBound = false
                vmService = null
            }

            // Show onboarding to select new image
            showOnboardingScreen()
        }
    }

    /**
     * Apply the Zim Invader theme color palette to the terminal emulator.
     */
    private fun applyZimColorPalette() {
        val emulator = bridge.getEmulator() ?: return
        val colors = emulator.mColors.mCurrentColors

        // Set standard + bright ANSI colors (indices 0-15)
        for (i in ZIM_PALETTE.indices) {
            colors[i] = ZIM_PALETTE[i]
        }

        // Special color indices (256=fg, 257=bg, 258=cursor)
        colors[256] = 0xFF00FF88.toInt() // default fg = zim green
        colors[257] = 0xFF0A0A14.toInt() // default bg = zim dark
        colors[258] = 0xFF00FF88.toInt() // cursor = zim green
    }

    override fun onStart() {
        super.onStart()
        if (::statsProvider.isInitialized) {
            statsProvider.resume(lifecycleScope)
        }
        // Reconnect output callback if already bound
        vmService?.setOutputCallback { text ->
            runOnUiThread {
                bridge.processOutput(text)
                terminalView.onScreenUpdated()
            }
        }
        // Restart cursor blinker
        if (::terminalView.isInitialized) {
            terminalView.setTerminalCursorBlinkerState(true, true)
        }
    }

    override fun onStop() {
        super.onStop()
        if (::statsProvider.isInitialized) {
            statsProvider.pause()
        }
        // Disconnect output callback so service doesn't hold Activity reference
        vmService?.clearOutputCallback()
        // Stop cursor blinker when not visible
        if (::terminalView.isInitialized) {
            terminalView.setTerminalCursorBlinkerState(false, false)
        }
    }

    /**
     * TerminalSessionClient — passed to TerminalEmulator constructor.
     * The emulator only calls getTerminalCursorStyle() and
     * onTerminalCursorStateChange() on this.
     */
    private val terminalSessionClient = object : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) {}
        override fun onTitleChanged(changedSession: TerminalSession) {}
        override fun onSessionFinished(finishedSession: TerminalSession) {}
        override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {}
        override fun onPasteTextFromClipboard(session: TerminalSession?) {}
        override fun onBell(session: TerminalSession) {}
        override fun onColorsChanged(session: TerminalSession) {}

        override fun onTerminalCursorStateChange(state: Boolean) {
            terminalView.setTerminalCursorBlinkerState(state, true)
        }

        override fun getTerminalCursorStyle(): Int {
            return TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK
        }

        override fun logError(tag: String?, message: String?) {}
        override fun logWarn(tag: String?, message: String?) {}
        override fun logInfo(tag: String?, message: String?) {}
        override fun logDebug(tag: String?, message: String?) {}
        override fun logVerbose(tag: String?, message: String?) {}
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
        override fun logStackTrace(tag: String?, e: Exception?) {}
    }

    /**
     * BridgeListener — receives callbacks from the bridge when the emulator
     * triggers events through TerminalOutput methods (bell, clipboard, etc.)
     */
    private val bridgeListener = object : WasmTerminalBridge.BridgeListener {
        override fun onTextChanged() {
            terminalView.onScreenUpdated()
            if (::statsProvider.isInitialized) {
                statsProvider.onOutputEvent()
            }
        }

        override fun onTitleChanged(title: String?) {}

        override fun onCopyTextToClipboard(text: String?) {
            if (text != null) {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Terminal", text))
            }
        }

        override fun onPasteTextFromClipboard() {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val clipData = clipboard.primaryClip
            if (clipData != null && clipData.itemCount > 0) {
                val paste = clipData.getItemAt(0).coerceToText(this@MainActivity)
                if (paste.isNotEmpty()) {
                    bridge.sendString(paste.toString())
                }
            }
        }

        override fun onBell() {
            // Could vibrate or play a sound
        }

        override fun onColorsChanged() {
            terminalView.invalidate()
        }
    }

    /**
     * TerminalViewClient — receives callbacks from TerminalView about
     * key events, touch events, and configuration queries.
     */
    private val terminalViewClient = object : TerminalViewClient {
        override fun onScale(scale: Float): Float = 1.0f

        override fun onSingleTapUp(e: MotionEvent) {
            // Show soft keyboard on single tap
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT)
        }

        override fun shouldBackButtonBeMappedToEscape(): Boolean = false

        override fun shouldEnforceCharBasedInput(): Boolean = true

        override fun shouldUseCtrlSpaceWorkaround(): Boolean = false

        override fun isTerminalViewSelected(): Boolean = true

        override fun copyModeChanged(copyMode: Boolean) {}

        override fun onKeyDown(keyCode: Int, e: KeyEvent, bridge: WasmTerminalBridge): Boolean {
            return false
        }

        override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false

        override fun onLongPress(event: MotionEvent): Boolean = false

        override fun readControlKey(): Boolean {
            val wasSticky = ctrlSticky
            if (wasSticky) {
                ctrlSticky = false
                btnCtrl.isSelected = false
                btnCtrl.setTextColor(0xFF00FFFF.toInt())
            }
            return wasSticky
        }

        override fun readAltKey(): Boolean = false

        override fun readShiftKey(): Boolean = false

        override fun readFnKey(): Boolean = false

        override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, bridge: WasmTerminalBridge): Boolean {
            if (::statsProvider.isInitialized) {
                statsProvider.onInputEvent()
            }
            return false
        }

        override fun onEmulatorSet() {
            // Re-apply color palette when emulator is (re)initialized
            applyZimColorPalette()
        }

        override fun logError(tag: String, message: String) {}
        override fun logWarn(tag: String, message: String) {}
        override fun logInfo(tag: String, message: String) {}
        override fun logDebug(tag: String, message: String) {}
        override fun logVerbose(tag: String, message: String) {}
        override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {}
        override fun logStackTrace(tag: String, e: Exception) {}
    }

    /**
     * Set up the IME animation callback system.
     */
    private fun setupImeAnimation() {
        // Layer 1: Root view defers IME insets during animation
        val deferringInsetsListener = RootViewDeferringInsetsCallback(
            persistentInsetTypes = WindowInsetsCompat.Type.systemBars(),
            deferredInsetTypes = WindowInsetsCompat.Type.ime(),
        )
        ViewCompat.setWindowInsetsAnimationCallback(contentRoot, deferringInsetsListener)
        ViewCompat.setOnApplyWindowInsetsListener(contentRoot, deferringInsetsListener)

        // Layer 2: Translate the terminal view and helper bar during IME animation
        ViewCompat.setWindowInsetsAnimationCallback(
            terminalView,
            TranslateDeferringInsetsAnimationCallback(
                view = terminalView,
                persistentInsetTypes = WindowInsetsCompat.Type.systemBars(),
                deferredInsetTypes = WindowInsetsCompat.Type.ime(),
            ),
        )
        ViewCompat.setWindowInsetsAnimationCallback(
            zimHelperBar,
            object : TranslateDeferringInsetsAnimationCallback(
                view = zimHelperBar,
                persistentInsetTypes = WindowInsetsCompat.Type.systemBars(),
                deferredInsetTypes = WindowInsetsCompat.Type.ime(),
                dispatchMode = WindowInsetsAnimationCompat.Callback.DISPATCH_MODE_CONTINUE_ON_SUBTREE,
            ) {
                override fun onEnd(animation: WindowInsetsAnimationCompat) {
                    super.onEnd(animation)
                    if (animation.typeMask and WindowInsetsCompat.Type.ime() != 0) {
                        updateHelperBarVisibility()
                    }
                }
            },
        )

        // Layer 2.5: Focus control on the terminal view
        ViewCompat.setWindowInsetsAnimationCallback(
            terminalView,
            ControlFocusInsetsAnimationCallback(terminalView),
        )

        // Track IME visibility changes to show/hide helper bar
        ViewCompat.setOnApplyWindowInsetsListener(terminalView) { _, insets ->
            updateHelperBarVisibility()
            insets
        }
    }

    private fun updateHelperBarVisibility() {
        val imeVisible = ViewCompat.getRootWindowInsets(contentRoot)
            ?.isVisible(WindowInsetsCompat.Type.ime()) == true
        zimHelperBar.visibility = if (imeVisible) View.VISIBLE else View.GONE
    }

    /**
     * Set up the Zim helper bar buttons. All input goes through the bridge.
     */
    private fun setupZimHelperBar() {
        btnCtrl.setOnClickListener {
            ctrlSticky = !ctrlSticky
            btnCtrl.isSelected = ctrlSticky
            btnCtrl.setTextColor(if (ctrlSticky) 0xFFFF1493.toInt() else 0xFF00FFFF.toInt())
        }

        btnPrevWord.setOnClickListener {
            if (bridge.isRunning) {
                statsProvider.onInputEvent()
                bridge.sendString("\u001Bb") // ESC b = backward-word
            }
        }

        btnNextWord.setOnClickListener {
            if (bridge.isRunning) {
                statsProvider.onInputEvent()
                bridge.sendString("\u001Bf") // ESC f = forward-word
            }
        }

        btnUp.setOnClickListener {
            if (bridge.isRunning) {
                statsProvider.onInputEvent()
                bridge.sendString("\u001B[A") // ESC[A = cursor up
            }
        }

        btnDown.setOnClickListener {
            if (bridge.isRunning) {
                statsProvider.onInputEvent()
                bridge.sendString("\u001B[B") // ESC[B = cursor down
            }
        }

        btnTab.setOnClickListener {
            if (bridge.isRunning) {
                statsProvider.onInputEvent()
                bridge.sendString("\t") // TAB
            }
        }

        btnEsc.setOnClickListener {
            if (bridge.isRunning) {
                statsProvider.onInputEvent()
                bridge.sendString("\u001B") // ESC
            }
        }
    }

    private fun setupStatusGauge() {
        statsProvider = SystemStatsProvider(this)
        statsProvider.start(lifecycleScope) { data ->
            gaugeState.value = data
        }

        val composeView = findViewById<ComposeView>(R.id.statusGauge)
        composeView.setContent {
            ZimStatusGauge(data = gaugeState)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::statsProvider.isInitialized) {
            statsProvider.stop()
        }
        if (serviceBound) {
            vmService?.clearOutputCallback()
            unbindService(serviceConnection)
            serviceBound = false
        }
    }
}

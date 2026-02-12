package com.example.c2wdemo

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.example.c2wdemo.gauge.GaugeData
import com.example.c2wdemo.gauge.SystemStatsProvider
import com.example.c2wdemo.gauge.ZimStatusGauge
import com.example.c2wdemo.ime.RootViewDeferringInsetsCallback
import com.example.c2wdemo.ime.TranslateDeferringInsetsAnimationCallback
import com.example.c2wdemo.terminal.FriscyTerminalBridge
import com.example.c2wdemo.terminal.FriscyTerminalView

class MainActivity : AppCompatActivity() {

    private lateinit var terminalView: FriscyTerminalView
    private lateinit var contentRoot: View
    private lateinit var zimHelperBar: LinearLayout
    private lateinit var btnCtrl: TextView
    private lateinit var btnPrevWord: TextView
    private lateinit var btnNextWord: TextView
    private lateinit var btnUp: TextView
    private lateinit var statsProvider: SystemStatsProvider
    private val gaugeState = mutableStateOf(GaugeData())

    /** The bridge connecting friscy runtime to the Termux TerminalEmulator. */
    private lateinit var bridge: FriscyTerminalBridge

    /** Sticky Ctrl mode: next character typed is sent as a control character. */
    private var ctrlSticky = false

    /** Bound VmService reference. */
    private var vmService: VmService? = null
    private var serviceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as VmService.LocalBinder
            vmService = localBinder.service
            serviceBound = true

            // Replay buffered output from service (output produced while UI was away)
            val buffered = localBinder.service.getBufferedOutput()
            if (buffered.isNotEmpty()) {
                feedOutput(buffered)
            }

            // Register for live output
            localBinder.service.setOutputCallback { text ->
                runOnUiThread {
                    feedOutput(text)
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            vmService = null
            serviceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Immersive mode
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.statusBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        terminalView = findViewById(R.id.terminalView)
        contentRoot = findViewById(R.id.contentRoot)
        zimHelperBar = findViewById(R.id.zimHelperBar)
        btnCtrl = findViewById(R.id.btnCtrl)
        btnPrevWord = findViewById(R.id.btnPrevWord)
        btnNextWord = findViewById(R.id.btnNextWord)
        btnUp = findViewById(R.id.btnUp)

        // Create the terminal bridge
        bridge = FriscyTerminalBridge(object : FriscyTerminalBridge.BridgeListener {
            override fun onScreenUpdated() {
                terminalView.invalidate()
            }
            override fun onTitleChanged(title: String) {}
            override fun onBell() {}
            override fun onCopyText(text: String) {}
            override fun onPasteRequest() {}
        })
        terminalView.bridge = bridge

        setupImeAnimation()
        setupZimHelperBar()
        setupStatusGauge()

        // Start and bind to VmService, forwarding image parameters
        val serviceIntent = Intent(this, VmService::class.java).apply {
            putExtra(ImagePickerActivity.EXTRA_IMAGE_SOURCE,
                intent.getStringExtra(ImagePickerActivity.EXTRA_IMAGE_SOURCE)
                    ?: ImagePickerActivity.SOURCE_ASSET)
            putExtra(ImagePickerActivity.EXTRA_ASSET_NAME,
                intent.getStringExtra(ImagePickerActivity.EXTRA_ASSET_NAME) ?: "rootfs.tar")
            putExtra(ImagePickerActivity.EXTRA_FILE_PATH,
                intent.getStringExtra(ImagePickerActivity.EXTRA_FILE_PATH))
            putExtra(ImagePickerActivity.EXTRA_ENTRY_POINT,
                intent.getStringExtra(ImagePickerActivity.EXTRA_ENTRY_POINT) ?: "/bin/sh")
        }
        startService(serviceIntent)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onStart() {
        super.onStart()
        if (::statsProvider.isInitialized) {
            statsProvider.resume(lifecycleScope)
        }
        vmService?.setOutputCallback { text ->
            runOnUiThread { feedOutput(text) }
        }
    }

    override fun onStop() {
        super.onStop()
        if (::statsProvider.isInitialized) {
            statsProvider.pause()
        }
        vmService?.clearOutputCallback()
    }

    /**
     * Feed output from the friscy runtime into the terminal emulator.
     * The TerminalEmulator handles all ANSI escape sequence parsing.
     */
    private fun feedOutput(text: String) {
        if (::statsProvider.isInitialized) {
            statsProvider.onOutputEvent()
        }
        bridge.feedOutput(text)
    }

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

        // Track IME visibility changes
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

    private fun setupZimHelperBar() {
        btnCtrl.setOnClickListener {
            ctrlSticky = !ctrlSticky
            btnCtrl.isSelected = ctrlSticky
            btnCtrl.setTextColor(if (ctrlSticky) 0xFFFF1493.toInt() else 0xFF00FFFF.toInt())
        }

        btnPrevWord.setOnClickListener {
            statsProvider.onInputEvent()
            terminalView.sendInput("\u001Bb")
        }

        btnNextWord.setOnClickListener {
            statsProvider.onInputEvent()
            terminalView.sendInput("\u001Bf")
        }

        btnUp.setOnClickListener {
            statsProvider.onInputEvent()
            terminalView.sendInput("\u001B[A")
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

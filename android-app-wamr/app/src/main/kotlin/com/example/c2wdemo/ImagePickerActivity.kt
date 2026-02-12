package com.example.c2wdemo

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class ImagePickerActivity : ComponentActivity() {

    private lateinit var imageManager: ImageManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        imageManager = ImageManager(this)

        setContent {
            ImagePickerScreen()
        }
    }

    @Composable
    private fun ImagePickerScreen() {
        var images by remember { mutableStateOf<List<ContainerImage>>(emptyList()) }
        var downloadingId by remember { mutableStateOf<String?>(null) }
        var downloadProgress by remember { mutableFloatStateOf(0f) }
        var downloadError by remember { mutableStateOf<String?>(null) }

        LaunchedEffect(Unit) {
            images = imageManager.loadRegistry()
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(ZimColors.Background)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                // Header
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = "friscy",
                    color = ZimColors.GreenTerminal,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = "RISC-V Container Runtime",
                    color = ZimColors.Cyan.copy(alpha = 0.7f),
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(24.dp))

                // Error banner
                downloadError?.let { error ->
                    Text(
                        text = error,
                        color = ZimColors.PinkBright,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        textAlign = TextAlign.Center,
                    )
                }

                // Image cards
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(images, key = { it.id }) { image ->
                        val isAvailable = imageManager.isAvailable(image)
                        val isDownloading = downloadingId == image.id

                        ImageCard(
                            image = image,
                            isAvailable = isAvailable,
                            isDownloading = isDownloading,
                            downloadProgress = if (isDownloading) downloadProgress else 0f,
                            onLaunch = { launchImage(image) },
                            onDownload = {
                                downloadError = null
                                downloadingId = image.id
                                downloadProgress = 0f
                                lifecycleScope.launch {
                                    image.let { img ->
                                        imageManager.download(img).collect { state ->
                                            when (state) {
                                                is DownloadState.Progress -> {
                                                    downloadProgress = if (state.totalBytes > 0) {
                                                        state.bytesDownloaded.toFloat() / state.totalBytes
                                                    } else 0.5f
                                                }
                                                is DownloadState.Complete -> {
                                                    downloadingId = null
                                                    // Trigger recompose by refreshing list
                                                    images = imageManager.loadRegistry()
                                                }
                                                is DownloadState.Error -> {
                                                    downloadingId = null
                                                    downloadError = "Failed: ${state.message}"
                                                }
                                            }
                                        }
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun ImageCard(
        image: ContainerImage,
        isAvailable: Boolean,
        isDownloading: Boolean,
        downloadProgress: Float,
        onLaunch: () -> Unit,
        onDownload: () -> Unit,
    ) {
        val accentColor = Color(image.accentColor)
        val animatedProgress by animateFloatAsState(
            targetValue = downloadProgress,
            animationSpec = tween(200),
            label = "progress",
        )
        val shape = RoundedCornerShape(12.dp)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .border(1.dp, accentColor.copy(alpha = 0.3f), shape)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            ZimColors.Frame,
                            ZimColors.Frame.copy(alpha = 0.8f),
                        )
                    )
                )
                .clickable(enabled = isAvailable && !isDownloading) { onLaunch() }
                .padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Icon placeholder
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(accentColor.copy(alpha = 0.15f))
                        .border(1.dp, accentColor.copy(alpha = 0.4f), RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = iconForType(image.icon),
                        fontSize = 22.sp,
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = image.name,
                        color = accentColor,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        text = image.description,
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }

                // Action button
                when {
                    isDownloading -> {
                        CircularProgressIndicator(
                            progress = { animatedProgress },
                            color = accentColor,
                            trackColor = accentColor.copy(alpha = 0.2f),
                            modifier = Modifier.size(36.dp),
                            strokeWidth = 3.dp,
                        )
                    }
                    isAvailable -> {
                        Button(
                            onClick = onLaunch,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = accentColor.copy(alpha = 0.2f),
                                contentColor = accentColor,
                            ),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        ) {
                            Text(
                                text = "LAUNCH",
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                            )
                        }
                    }
                    image.downloadUrl != null -> {
                        Button(
                            onClick = onDownload,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = accentColor.copy(alpha = 0.1f),
                                contentColor = accentColor.copy(alpha = 0.7f),
                            ),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        ) {
                            Text(
                                text = "GET",
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                            )
                        }
                    }
                    else -> {
                        Text(
                            text = "SOON",
                            color = Color.White.copy(alpha = 0.3f),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                        )
                    }
                }
            }

            // Size info for available images
            if (image.sizeBytes > 0) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = formatSize(image.sizeBytes),
                    color = Color.White.copy(alpha = 0.3f),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }

            // Download progress bar
            if (isDownloading) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { animatedProgress },
                    color = accentColor,
                    trackColor = accentColor.copy(alpha = 0.1f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .clip(RoundedCornerShape(1.dp)),
                )
            }
        }
    }

    private fun launchImage(image: ContainerImage) {
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra(EXTRA_IMAGE_ID, image.id)
            putExtra(EXTRA_ENTRY_POINT, image.entryPoint)
            if (image.bundledAsset != null) {
                putExtra(EXTRA_IMAGE_SOURCE, SOURCE_ASSET)
                putExtra(EXTRA_ASSET_NAME, image.bundledAsset)
            } else {
                putExtra(EXTRA_IMAGE_SOURCE, SOURCE_FILE)
                putExtra(EXTRA_FILE_PATH, "${filesDir}/images/${image.id}.tar")
            }
        }
        startActivity(intent)
    }

    companion object {
        const val EXTRA_IMAGE_ID = "image_id"
        const val EXTRA_ENTRY_POINT = "entry_point"
        const val EXTRA_IMAGE_SOURCE = "image_source"
        const val EXTRA_ASSET_NAME = "asset_name"
        const val EXTRA_FILE_PATH = "file_path"
        const val SOURCE_ASSET = "asset"
        const val SOURCE_FILE = "file"

        private fun iconForType(icon: String): String = when (icon) {
            "terminal" -> "\u2588"  // block
            "sparkle" -> "\u2728"   // sparkle emoji
            "code" -> "</>"
            "brain" -> "\uD83E\uDDE0" // brain emoji
            else -> ">"
        }

        private fun formatSize(bytes: Long): String = when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> "%.1f MB".format(bytes.toDouble() / (1024 * 1024))
        }
    }
}

/** Invader Zim color palette for Compose. */
object ZimColors {
    val Background = Color(0xFF0A0A14)
    val Frame = Color(0xFF1A1A2E)
    val GreenTerminal = Color(0xFF00FF88)
    val PinkBright = Color(0xFFFF1493)
    val Cyan = Color(0xFF00FFFF)
    val Purple = Color(0xFFC77DFF)
}

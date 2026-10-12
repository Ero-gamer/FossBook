package com.eepiemi.materialbook.ui.components.settings

import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowInsetsControllerCompat
import com.eepiemi.materialbook.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDialog(
    onDismiss: () -> Unit,
    onReload: () -> Unit,
    onLogOut: () -> Unit = {},
    onSharePage: (() -> Unit)? = null,
) {
    val scrollState = rememberScrollState()

    Dialog(
        properties = DialogProperties(usePlatformDefaultWidth = false),
        onDismissRequest = onDismiss,
    ) {
        val view = LocalView.current
        val dialogWindowProvider = view.parent as? DialogWindowProvider
        dialogWindowProvider?.window?.setDimAmount(0f)
        // A zero dim still counts as dimming, and Android drops light status bar icons under a
        // dimming window: clear the flag too.
        dialogWindowProvider?.window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        // The dialog is its own window, and the status and navigation bar icons follow the
        // window on top: without this they stay white on a light Settings background.
        val lightBars = MaterialTheme.colorScheme.background.luminance() > 0.5f
        dialogWindowProvider?.window?.let { window ->
            WindowInsetsControllerCompat(window, view).apply {
                isAppearanceLightStatusBars = lightBars
                isAppearanceLightNavigationBars = lightBars
            }
        }

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    IconButton(
                        modifier = Modifier.align(Alignment.TopStart).size(22.dp),
                        onClick = { onDismiss() }
                    ) { Icon(Icons.AutoMirrored.Default.ArrowBackIos, "back button") }

                    onSharePage?.let { share ->
                        IconButton(
                            modifier = Modifier.align(Alignment.TopEnd).size(22.dp),
                            onClick = {
                                onDismiss()
                                share()
                            }
                        ) { Icon(Icons.Default.Share, stringResource(R.string.share_page)) }
                    }

                    Text(
                        stringResource(R.string.materialbook_settings),
                        style = MaterialTheme.typography.titleLarge,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
            },
            floatingActionButton = {
                val isVisible by remember {
                    derivedStateOf {
                        scrollState.maxValue == 0 || scrollState.value < scrollState.maxValue
                    }
                }
                AnimatedVisibility(
                    isVisible,
                    enter = fadeIn() + scaleIn(),
                    exit = fadeOut() + scaleOut()
                ) {
                    ExtendedFloatingActionButton(
                        onClick = {
                            onReload()
                            onDismiss()
                        },
                        text = {
                            Text(
                                stringResource(R.string.apply_immediately)
                            )
                        },
                        icon = {
                            Icon(Icons.Default.Bolt, null)
                        }
                    )
                }
            }
        ) { paddingValues ->
            SettingsContent(
                modifier = Modifier
                    .padding(paddingValues)
                    .verticalScroll(scrollState)
                    .padding(16.dp),
                onLogOut = {
                    onDismiss()
                    onLogOut()
                }
            )
        }

    }
}



package com.xenonware.store

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xenon.mylibrary.theme.QuicksandTitleVariable
import com.xenonware.store.viewmodel.AppListViewModel

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MainScreen(viewModel: AppListViewModel) {
    val appItem by viewModel.appItemUpdated.observeAsState()
    val currentAppItem = appItem
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.refreshAppItem(viewModel.storeAppItem)
    }

    AnimatedGradientBackground(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = stringResource(id = R.string.welcome),
                    fontFamily = QuicksandTitleVariable,
                    color = Color.White,
                    fontSize = 48.sp,
                    lineHeight = 48.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 32.dp)
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 40.dp)
                        .height(96.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val isDownloading = currentAppItem?.state == AppEntryState.DOWNLOADING
                    Button(
                        onClick = {
                            if (currentAppItem != null && !isDownloading) {
                                viewModel.downloadAppItem(currentAppItem, context)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        ),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        if (!isDownloading) {
                            Text(
                                fontFamily = QuicksandTitleVariable,
                                text = "Download",
                                fontSize = 32.sp
                            )
                        }
                    }

                    if (isDownloading) {
                        val progressModifier = Modifier
                            .fillMaxWidth()
                            .height(64.dp)
                            .padding(horizontal = 16.dp)
                            .clip(RoundedCornerShape(37.dp))

                        if (currentAppItem.fileSize > 0) {
                            LinearProgressIndicator(
                                progress = { currentAppItem.bytesDownloaded.toFloat() / currentAppItem.fileSize.toFloat() },
                                modifier = progressModifier,
                                color = MaterialTheme.colorScheme.onSurface,
                                trackColor = Color.Transparent
                            )
                        } else {
                            LinearProgressIndicator(
                                modifier = progressModifier,
                                color = MaterialTheme.colorScheme.onSurface,
                                trackColor = Color.Transparent
                            )
                        }
                    }
                }
            }
        }
    }
}

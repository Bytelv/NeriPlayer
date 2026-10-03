package moe.ouom.neriplayer.ui.screen.debug

/*
 * NeriPlayer - A unified Android player for streaming music and videos from multiple online platforms.
 * Copyright (C) 2025-2025 NeriPlayer developers
 * https://github.com/cwuom/NeriPlayer
 *
 * This software is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this software.
 * If not, see <https://www.gnu.org/licenses/>.
 *
 * File: moe.ouom.neriplayer.ui.screen.debug/SearchApiProbeScreen
 * Created: 2025/8/17
 */

import android.app.Application
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.compose.AsyncImage
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.model.music.MusicPlatform
import moe.ouom.neriplayer.data.model.music.SongSearchInfo
import moe.ouom.neriplayer.ui.haptic.HapticIconButton
import moe.ouom.neriplayer.ui.util.toKugouQueueSong
import moe.ouom.neriplayer.ui.navigation.LocalMiniPlayerHeight
import moe.ouom.neriplayer.ui.viewmodel.debug.SearchApiProbeViewModel

@Composable
fun SearchApiProbeScreen() {
    val context = LocalContext.current
    val vm: SearchApiProbeViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                val app = context.applicationContext as Application
                SearchApiProbeViewModel(app)
            }
        }
    )

    val ui by vm.ui.collectAsState()
    val scroll = rememberScrollState()
    val miniH = LocalMiniPlayerHeight.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .padding(bottom = miniH),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = stringResource(CoreCommonR.string.debug_search_probe),
            style = MaterialTheme.typography.titleLarge
        )
        Text(
            text = stringResource(CoreCommonR.string.debug_search_desc),
            style = MaterialTheme.typography.bodyMedium
        )

        OutlinedTextField(
            value = ui.keyword,
            onValueChange = vm::onKeywordChange,
            label = { Text(stringResource(CoreCommonR.string.debug_search_keyword_label)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
            )
        ) {
            Column(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val buttonEnabled = !ui.running && ui.keyword.isNotBlank()

                Button(
                    onClick = { vm.callSearchAndCopy(MusicPlatform.CLOUD_MUSIC) },
                    enabled = buttonEnabled,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(CoreCommonR.string.debug_search_netease)) }

                Button(
                    onClick = { vm.callSearchAndCopy(MusicPlatform.QQ_MUSIC) },
                    enabled = buttonEnabled,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(CoreCommonR.string.debug_search_qq)) }

                Button(
                    onClick = vm::searchKugou,
                    enabled = !ui.kugouSearching && ui.keyword.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(CoreCommonR.string.platform_kugou)) }


                if (ui.running || ui.kugouSearching) {
                    Spacer(Modifier.height(8.dp))
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                    Spacer(Modifier.height(8.dp))
                }
            }
        }

        if (ui.kugouResults.isNotEmpty()) {
            KugouSearchResultList(results = ui.kugouResults)
        }

        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
            )
        ) {
            Column(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(stringResource(CoreCommonR.string.debug_status, ui.lastMessage), style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = ui.lastJsonPreview.ifBlank { stringResource(CoreCommonR.string.debug_search_preview_hint) },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * 酷狗搜索结果列表
 *
 * 歌曲元数据由搜索接口给出; 真正的播放/下载直链在点击加号后才按需解析,
 * 未登录时播放器会提示去设置页登录。
 */
@Composable
private fun KugouSearchResultList(results: List<SongSearchInfo>) {
    val context = LocalContext.current
    val addedMessage = stringResource(CoreCommonR.string.kugou_added_to_queue)

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
        )
    ) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(CoreCommonR.string.platform_kugou),
                style = MaterialTheme.typography.titleMedium
            )
            results.forEach { result ->
                ListItem(
                    headlineContent = { Text(result.songName, maxLines = 1) },
                    supportingContent = {
                        Text(
                            text = listOfNotNull(
                                result.singer.takeIf { it.isNotBlank() },
                                result.albumName?.takeIf { it.isNotBlank() }
                            ).joinToString(" · "),
                            maxLines = 1
                        )
                    },
                    leadingContent = {
                        AsyncImage(
                            model = result.coverUrl?.replaceFirst("http://", "https://"),
                            contentDescription = result.songName,
                            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp))
                        )
                    },
                    trailingContent = {
                        HapticIconButton(
                            onClick = {
                                PlayerManager.addToQueueEnd(result.toKugouQueueSong())
                                Toast.makeText(context, addedMessage, Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Add,
                                contentDescription = stringResource(CoreCommonR.string.kugou_add_to_queue)
                            )
                        }
                    }
                )
            }
        }
    }
}

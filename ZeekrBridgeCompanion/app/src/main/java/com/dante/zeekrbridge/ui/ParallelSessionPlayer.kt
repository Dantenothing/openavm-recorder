package com.dante.zeekrbridge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.AudioAttributes
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlayer
import com.dante.zeekrbridge.core.*
import com.dante.zeekrbridge.player.*
import io.github.dantenothing.avmtransfer.protocol.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

/** A single session clock drives up to three decoders. Missing channel intervals remain empty. */
@Composable
internal fun ParallelSessionPlayer(
    segments: List<IndexedMediaSegment>, seekRequest: PlaybackSeekRequest?, obstructed: Boolean,
    onActiveMediaChanged: (String) -> Unit, modifier: Modifier = Modifier,
    additionalContent: @Composable () -> Unit = {},
) {
    val context = LocalContext.current
    var loadAttempt by remember(segments) { mutableIntStateOf(0) }
    val loaded by produceState<SessionLoadResult<List<IndexedMediaSegment>, LoadedSessionPlayback>?>(null, segments, loadAttempt) {
        value = loadSessionResult(segments, loadAttempt) { withContext(Dispatchers.IO) { loadSessionPlayback(context, segments) } }
    }
    val result = loaded?.takeIf { it.matches(segments, loadAttempt) }
    val data = result?.data
    if (data == null) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (result == null) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(t("Reading session timing…", "正在读取录像时间…"))
            } else {
                Text(t("Could not read this session's timing. Retry or open a segment individually.", "无法读取此行程的时间信息，请重试或单独打开分段。"))
                TextButton(onClick = { loadAttempt++ }) { Text(t("Retry", "重试")) }
                additionalContent()
            }
        }
        return
    }
    val timeline = data.timeline
    if (timeline.channelIds.isEmpty()) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(t("No readable timed video is available. Restore the missing files or open a segment individually.",
                "没有可读取且有时间信息的视频。请恢复缺失文件，或单独打开分段。"))
            TextButton(onClick = { loadAttempt++ }) { Text(t("Retry", "重试")) }
            additionalContent()
        }
        return
    }
    var channel by rememberSaveable(timeline.sessionId) { mutableStateOf(timeline.channelIds.first()) }
    var position by rememberSaveable(timeline.sessionId) { mutableLongStateOf(initialPhoneSessionPosition(timeline, channel)) }
    var wantsToPlay by rememberSaveable(timeline.sessionId) { mutableStateOf(true) }
    var speed by rememberSaveable(timeline.sessionId) { mutableFloatStateOf(1f) }
    var muted by rememberSaveable(timeline.sessionId) { mutableStateOf(false) }
    var overview by rememberSaveable(timeline.sessionId) { mutableStateOf(false) }
    var seekSerial by remember { mutableIntStateOf(0) }
    var seekCount by remember { mutableIntStateOf(0) }
    var playbackFrames by remember { mutableIntStateOf(0) }
    var playbackErrors by remember { mutableIntStateOf(0) }
    var slider by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var foreground by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> foreground = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val selected = timeline.seek(channel, position)
    val activeEntry = selected?.segmentId?.let(data.entries::get)
    val currentSelected by rememberUpdatedState(selected)
    val playing = wantsToPlay && foreground && !obstructed && selected != null
    val view = LocalView.current
    DisposableEffect(view, playing) {
        val before = view.keepScreenOn
        view.keepScreenOn = playing
        onDispose { view.keepScreenOn = before }
    }
    fun seek(at: Long) {
        position = at.coerceIn(0L, timeline.durationMs)
        seekSerial++
        seekCount++
    }
    LaunchedEffect(selected?.segmentId) { selected?.segmentId?.let(onActiveMediaChanged) }
    LaunchedEffect(channel, selected == null) { if (selected == null) wantsToPlay = false }
    LaunchedEffect(seekRequest) {
        seekRequest?.let { request ->
            timeline.segments.find { it.id == request.mediaId }?.let { window ->
                channel = window.channelId
                timeline.mediaToSession(window.id, 0)?.let(::seek)
                wantsToPlay = true
            }
        }
    }
    val attachment by produceState<Pair<String, DriveDataBundle?>?>(null, activeEntry) {
        value = activeEntry?.let { entry -> withContext(Dispatchers.IO) {
            entry.id to entry.uri.path?.let { DriveDataFiles.load(File(it)) }
        } }
    }
    val bundle = attachment?.takeIf { it.first == activeEntry?.id }?.second
    val frame = recordedPlaylistFrame(bundle, selected?.mediaPositionMs,
        timeline.segments.find { it.id == selected?.segmentId }?.mediaDurationMs ?: 0L)
    val status = when {
        activeEntry == null -> DriveAttachmentStatus.MISSING
        attachment?.first != activeEntry.id -> DriveAttachmentStatus.LOADING
        bundle == null -> DriveAttachmentStatus.MISSING
        else -> DriveAttachmentStatus.AVAILABLE
    }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val landscape = maxWidth >= 600.dp && maxWidth > maxHeight
        val videoHeight = if (landscape) (maxHeight - 200.dp).coerceAtLeast(100.dp)
            else (maxHeight * .30f).coerceIn(100.dp, 240.dp)
        val video: @Composable () -> Unit = {
            Column {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SessionPlaybackTimeline.CHANNELS.forEach { choice ->
                        FilterChip(channel == choice, { channel = choice; seekSerial++ },
                            label = { Text(channelLabel(choice)) }, enabled = choice in timeline.channelIds)
                    }
                }
                Box(Modifier.fillMaxWidth().height(videoHeight).background(Color.Black)) {
                    SessionVideoPane(activeEntry, selected?.mediaPositionMs ?: 0L, seekSerial, playing, speed, muted,
                        true, Modifier.fillMaxSize(),
                        onPosition = { id, local, ended ->
                            val window = timeline.segments.find { it.id == id }
                            if (currentSelected?.segmentId == id && window != null) timeline.mediaToSession(id, local.coerceAtMost(window.mediaDurationMs))?.let { at ->
                                position = at.coerceAtMost(timeline.durationMs)
                                if (ended && position >= timeline.durationMs) wantsToPlay = false
                            }
                        }, onFrame = { playbackFrames++ }, onFailure = { playbackErrors++; wantsToPlay = false },
                        onPauseRequest = { wantsToPlay = false })
                    if (selected == null) Column(Modifier.align(Alignment.Center).padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(t("No video from this camera at this time", "此时间没有该摄像头的视频"), color = Color.White)
                        nextSessionVideoPosition(timeline, channel, position)?.let { next ->
                            TextButton(onClick = { seek(next) }) { Text(t("Next available segment", "下一可用分段")) }
                        }
                    }
                    RecordedVideoHud(frame, Modifier.align(Alignment.BottomStart).padding(6.dp), compact = overview || videoHeight < 300.dp)
                }
                PlaybackControls(if (dragging) slider.toLong() else position, timeline.durationMs, playing, speed, muted,
                    onScrub = { dragging = true; slider = it }, onScrubFinished = { seek(slider.toLong()); dragging = false },
                    onBackTen = { seek(position - 10_000) }, onForwardTen = { seek(position + 10_000) },
                    onTogglePlay = { if (position >= timeline.durationMs) seek(0); wantsToPlay = !wantsToPlay },
                    onSpeed = { speed = it }, onMute = { muted = !muted })
            }
        }
        val details: @Composable () -> Unit = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(recordedClockLabel(bundle?.videoClockAnchor),
                    style = MaterialTheme.typography.bodySmall)
                if (timeline.channelIds.size > 1) {
                    TextButton(onClick = { overview = !overview }) { Text(if (overview) t("Hide camera overview", "收起摄像头总览") else t("Show camera overview", "显示摄像头总览")) }
                    if (overview) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        timeline.channelIds.filter { it != channel }.take(2).forEach { other ->
                            val otherSeek = timeline.seek(other, position)
                            Column(Modifier.weight(1f)) {
                                Text(channelLabel(other), style = MaterialTheme.typography.labelMedium)
                                Box(Modifier.fillMaxWidth().height(110.dp).background(Color.Black), contentAlignment = Alignment.Center) {
                                    SessionVideoPane(otherSeek?.segmentId?.let(data.entries::get), otherSeek?.mediaPositionMs ?: 0,
                                        seekSerial, playing, speed, true, false, Modifier.fillMaxSize())
                                    if (otherSeek == null) Text(t("No footage", "无画面"), color = Color.White)
                                }
                            }
                        }
                    }
                }
                SessionDrivePanel(data, bundle, frame, status, position, channel, seekCount, playbackFrames,
                    playbackErrors, ::seek)
                if (data.unavailableFiles > 0 || data.limitedFiles > 0) Text(t(
                    "Unavailable files: {0} · over review limit: {1}", "无法播放的文件：{0} · 超过查看上限：{1}",
                    data.unavailableFiles, data.limitedFiles), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                additionalContent()
            }
        }
        if (landscape) Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(.56f)) { video() }
            Column(Modifier.weight(.44f).fillMaxHeight().verticalScroll(rememberScrollState())) { details() }
        } else Column(Modifier.fillMaxSize()) {
            video()
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 8.dp)) { details() }
        }
    }
}

/** Main decoder reports its media clock; auxiliary decoders track it and are always muted. */
@Composable
private fun SessionVideoPane(
    entry: PlaybackEntry?, positionMs: Long, seekSerial: Int, playing: Boolean, speed: Float, muted: Boolean,
    main: Boolean, modifier: Modifier, onPosition: (String, Long, Boolean) -> Unit = { _, _, _ -> },
    onFrame: () -> Unit = {}, onFailure: () -> Unit = {}, onPauseRequest: () -> Unit = {},
) {
    val context = LocalContext.current
    val player = remember { ExoPlayer.Builder(context).setAudioAttributes(AudioAttributes.DEFAULT, main)
        .setHandleAudioBecomingNoisy(main).build().apply { volume = 0f } }
    var failure by remember(entry?.id) { mutableStateOf<String?>(null) }
    var mode by remember { mutableIntStateOf(FourLaneGlView.MODE_GRID) }
    var surfaceReady by remember(entry?.id) { mutableStateOf(false) }
    val currentEntry by rememberUpdatedState(entry)
    val currentOnPosition by rememberUpdatedState(onPosition)
    val currentOnFrame by rememberUpdatedState(onFrame)
    val currentOnFailure by rememberUpdatedState(onFailure)
    val currentOnPauseRequest by rememberUpdatedState(onPauseRequest)
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (main && state == Player.STATE_ENDED) currentEntry?.let {
                    if (player.currentMediaItem?.mediaId == it.id) currentOnPosition(it.id, player.duration.coerceAtLeast(0), true)
                }
            }
            override fun onRenderedFirstFrame() { currentOnFrame() }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!playWhenReady && reason in setOf(Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS,
                    Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY)) currentOnPauseRequest()
            }
            override fun onPlayerError(error: PlaybackException) {
                failure = error.errorCodeName
                currentOnFailure()
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener); player.release() }
    }
    LaunchedEffect(player, entry) {
        player.pause()
        if (entry == null) player.clearMediaItems() else {
            player.setMediaItem(MediaItem.Builder().setMediaId(entry.id).setUri(entry.uri).build(), positionMs)
            if (!entry.layoutKind.isFourLane || surfaceReady) player.prepare()
        }
    }
    LaunchedEffect(player, entry, seekSerial) {
        if (entry != null) {
            player.seekTo(positionMs)
            if (failure != null) {
                failure = null
                if (!entry.layoutKind.isFourLane || surfaceReady) player.prepare()
            }
        }
    }
    LaunchedEffect(player, entry, positionMs) {
        if (!main && entry != null && abs(player.currentPosition - positionMs) > 350) player.seekTo(positionMs)
    }
    LaunchedEffect(player, entry, playing, speed, muted, failure) {
        player.setPlaybackSpeed(speed)
        player.volume = if (muted || !main) 0f else 1f
        player.playWhenReady = playing && entry != null && failure == null
    }
    LaunchedEffect(player, entry) {
        while (entry != null && main) {
            if (player.currentMediaItem?.mediaId == entry.id && player.playbackState == Player.STATE_READY)
                currentOnPosition(entry.id, player.currentPosition.coerceAtLeast(0), false)
            delay(100)
        }
    }
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val ratio = if (entry?.layoutKind?.isFourLane == true) 1f else
            entry?.originalWidth?.toFloat()?.let { width -> entry.originalHeight?.takeIf { it > 0 }?.let { width / it } }
        val frame = fitVideoFrame(maxWidth.value, maxHeight.value, ratio ?: (16f / 9f))
        if (entry != null) key(entry.id) {
            if (entry.layoutKind.isFourLane) FourLaneVideoSurface(player, entry, mode, FourLaneLensMode.FISHEYE,
                { if (main) mode = it }, currentOnFrame, {
                    surfaceReady = true
                    if (player.mediaItemCount > 0 && player.playbackState == Player.STATE_IDLE) player.prepare()
                }, { failure = it; currentOnFailure() }, Modifier.size(frame.width.dp, frame.height.dp))
            else PlainVideoSurface(player, Modifier.size(frame.width.dp, frame.height.dp))
        }
        failure?.let { Text(t("Video unavailable · retry by seeking", "视频不可用 · 重新定位后重试"), color = Color.White, modifier = Modifier.padding(8.dp)) }
    }
}

internal fun channelLabel(channel: String): String = when (channel) {
    "SURROUND" -> t("Surround", "环视")
    "FRONT_CABIN" -> t("Front cabin", "前舱")
    "REAR_CABIN" -> t("Rear cabin", "后舱")
    else -> t("Camera", "摄像头")
}

internal fun recordedClockLabel(anchor: VideoClockAnchor?): String = when (anchor?.source) {
    "CAMERA_REALTIME" -> t("Shared camera clock · sensor observations have uncertainty", "摄像头共用时钟 · 传感器观测仍有时间误差")
    "CODEC_OUTPUT_OBSERVED", "CALLBACK_OBSERVED" -> t("Observed video clock · pipeline delay is unverified", "已记录视频时钟 · 编码延迟尚未验证")
    else -> t("Session clock · segment-start approximation", "行程时钟 · 按分段起点近似对齐")
}

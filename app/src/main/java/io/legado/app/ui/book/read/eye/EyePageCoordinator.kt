// 眼动翻页协调器：逻辑移植自 legadoC 的 EyePageTurner（View/Activity 写法），
// 界面改由 Compose 状态驱动。摄像头、识别与校准流程与来源一致：
// 校准三阶段 TOP → BOTTOM → MIDPOINT，中点校验通过才保存；失败可重试一次；
// 每阶段准备 3s、超时 20s；stale 帧丢弃；readingBlockReason 暂停识别。
// SPDX-License-Identifier: GPL-3.0-only
package io.legado.app.ui.book.read.eye

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.utils.toastOnUi
import java.util.Locale
import kotlin.math.abs

/** 校准圆点的纵向位置（落点用 0..1 的同一映射）。 */
enum class EyeCalibrationStage { TOP, BOTTOM, MIDPOINT }

/** 落点圆点的样式快照：颜色恒定不透明，透明度单独由 alpha 控制。 */
data class EyeGazePointStyle(
    val color: Int,
    val alpha: Float,
    val sizeDp: Int,
)

/**
 * 主线程协调器。由阅读页宿主 remember 后持有；生命周期、权限与翻页动作由宿主接线，
 * 这里只负责摄像头、识别、校准与状态。
 */
class EyePageCoordinator(
    private val context: Context,
) {
    /** 宿主提供：当前是否可识别（菜单/弹层/搜索/朗读/自动翻页等原因返回非空即暂停）。 */
    var readingBlockReason: () -> String? = { null }

    /** 宿主提供：当前是否可校准（已恢复、有窗口焦点、菜单与弹层均关闭）。 */
    var canCalibrate: () -> Boolean = { true }

    /** 宿主提供：当前屏幕方向对应的旋转角度（Camera2 传感器补偿用）。 */
    var displayDegrees: () -> Int = { 0 }

    /** 识别到「底部停留 → 向上看」时翻下一页。 */
    var onNextPage: () -> Unit = {}

    /** 启动/识别/校准失败：宿主据此关开关并提示。 */
    var onError: (Throwable) -> Unit = {}

    /** 校准通过并保存后：宿主经 ViewModel + ReadSettingsGateway 落盘（协调器不直写配置）。 */
    var onCalibrationSaved: (String) -> Unit = {}

    /** 用户在校准中取消：宿主据此关开关。 */
    var onCancel: () -> Unit = {}

    private val handler = Handler(Looper.getMainLooper())
    private val detector = GazeGestureDetector()
    private val calibration = GazeCalibration()
    private var camera: EyeCameraTracker? = null
    private var generation = 0

    private val _isCalibrating = mutableStateOf(false)
    val isCalibrating: State<Boolean> = _isCalibrating

    private val _calibrationStage = mutableStateOf(EyeCalibrationStage.TOP)
    val calibrationStage: State<EyeCalibrationStage> = _calibrationStage

    private val _calibrationPreparing = mutableStateOf(false)
    val calibrationPreparing: State<Boolean> = _calibrationPreparing

    /** 准备阶段剩余秒数（非准备阶段为 0）。 */
    private val _countdownSeconds = mutableIntStateOf(0)
    val countdownSeconds: State<Int> = _countdownSeconds

    private val _instructionText = mutableStateOf("")
    val instructionText: State<String> = _instructionText

    private val _statusText = mutableStateOf("")
    val statusText: State<String> = _statusText

    /** 落点纵向位置（0 = 顶部 56dp，1 = 阅读区底 40dp 上方）；null 表示不显示。 */
    private val _gazePosition = mutableFloatStateOf(Float.NaN)
    val gazePosition: State<Float> = _gazePosition

    private val _gazePointStyle = mutableStateOf(readGazePointStyle())
    val gazePointStyle: State<EyeGazePointStyle> = _gazePointStyle

    private val _readyHintVisible = mutableStateOf(false)
    val readyHintVisible: State<Boolean> = _readyHintVisible

    private val _running = mutableStateOf(false)
    val running: State<Boolean> = _running

    private var gazePointEnabled = false
    private var topPoint: GazeCalibrationPoint? = null
    private var bottomPoint: GazeCalibrationPoint? = null
    private var retryCount = 0
    private var retryHint: String? = null
    private var calibrationPaused = false
    private var calibrationReadyAt = 0L
    private val calibrationCountdown = object : Runnable {
        override fun run() {
            if (!_isCalibrating.value || !_calibrationPreparing.value) return
            if (!canCalibrate()) {
                pauseCalibration()
                return
            }
            if (calibrationPaused) {
                beginCalibrationStage()
                return
            }
            val remaining = calibrationReadyAt - SystemClock.uptimeMillis()
            if (remaining > 0L) {
                _countdownSeconds.intValue = ((remaining + 999L) / 1000L).toInt()
                _statusText.value = context.getString(
                    R.string.eye_page_calibration_countdown, _countdownSeconds.intValue
                )
                handler.postDelayed(this, minOf(remaining, 1000L))
            } else {
                _calibrationPreparing.value = false
                _countdownSeconds.intValue = 0
                updateTarget()
                lastIssue = R.string.eye_page_sampling
                _statusText.value = context.getString(lastIssue, 0)
                handler.postDelayed(timeout, CALIBRATION_TIMEOUT_MS)
            }
        }
    }
    private var lastIssue = R.string.eye_page_camera_starting
    private var staleFrames = 0
    private var receivedFrames = 0
    private var lastReadingLog = 0L
    private val readingStates = linkedMapOf<String, Int>()
    private var minPosition = Float.POSITIVE_INFINITY
    private var maxPosition = Float.NEGATIVE_INFINITY
    private var readyHintVisibleBacking = false
    private val hintTimeout = Runnable {
        detector.expireReadyWindow(SystemClock.uptimeMillis())
        updateReadyHint(detector.armed)
    }

    private val timeout = Runnable {
        if (_isCalibrating.value) {
            AppLog.put(
                "眼动校准超时：stage=${stage()} frames=$receivedFrames stale=$staleFrames " +
                    "progress=${calibration.progress} issue=${issueMessage()}"
            )
            onError(
                IllegalStateException(
                    context.getString(R.string.eye_page_calibration_detail, issueMessage())
                )
            )
        }
    }

    fun setGazePointEnabled(enabled: Boolean) {
        gazePointEnabled = enabled
        if (!enabled) hideGazePoint()
    }

    fun refreshGazePointStyle() {
        _gazePointStyle.value = readGazePointStyle()
    }

    private fun readGazePointStyle(): EyeGazePointStyle {
        val color = ReadBookConfig.eyePageGazePointColor
        val size = ReadBookConfig.eyePageGazePointSize.coerceIn(MIN_GAZE_POINT_SIZE_DP, MAX_GAZE_POINT_SIZE_DP)
        val alpha = ReadBookConfig.eyePageGazePointAlpha.coerceIn(0, 100) / 100f
        return EyeGazePointStyle(color = color or 0xFF000000.toInt(), alpha = alpha, sizeDp = size)
    }

    fun recalibrate() {
        if (camera == null) {
            start()
            return
        }
        detector.calibrate(0f, 0f)
        updateReadyHint(false)
        hideGazePoint()
        if (_isCalibrating.value) hideCalibration()
        showCalibration()
    }

    /** 用户取消校准：收起 overlay，宿主关开关。 */
    fun cancelCalibration() {
        if (!_isCalibrating.value) return
        hideCalibration()
        onCancel()
    }

    fun start() {
        if (camera != null) return
        val current = ++generation
        detector.reset(SystemClock.uptimeMillis(), cooldown = true)
        updateReadyHint(false)
        if (!detector.calibrated) {
            val saved = GazeCalibrationProfile.decode(ReadBookConfig.eyePageCalibration)
            if (saved != null && detector.calibrate(saved.top, saved.bottom, saved.noise)) {
                detector.reset(SystemClock.uptimeMillis(), cooldown = true)
                AppLog.putDebug("眼动翻页已复用首次校准：top=${saved.top} bottom=${saved.bottom} noise=${saved.noise}")
            } else {
                showCalibration()
            }
        }
        camera = EyeCameraTracker(context.applicationContext, displayDegrees(), { observation, time ->
            if (current == generation) accept(observation, time)
        }, { error ->
            if (current == generation) onError(error)
        })
        _running.value = true
    }

    private fun accept(observation: GazeObservation, time: Long) {
        receivedFrames++
        val sample = observation.sample
        if (_isCalibrating.value) {
            if (!canCalibrate()) {
                pauseCalibration()
                return
            }
            if (calibrationPaused) {
                beginCalibrationStage()
                return
            }
            // 准备阶段的帧绝不能进入校准，即使晚到。
            if (_calibrationPreparing.value || time < calibrationReadyAt) return
        }
        // 丢弃 UI 线程繁忙期间排队的过期结果。
        if (SystemClock.uptimeMillis() - time > 350L) {
            staleFrames++
            lastIssue = R.string.eye_page_processing_slow
            _statusText.value = context.getString(lastIssue)
            detector.interruptTracking("stale_frame")
            detector.expireReadyWindow(SystemClock.uptimeMillis())
            updateReadyHint(detector.armed)
            calibration.reset()
            hideGazePoint()
            if (!_isCalibrating.value) recordReading(sample, time, "stale_frame")
            return
        }
        if (_isCalibrating.value) {
            val point = calibration.add(sample, time)
            lastIssue = when {
                calibration.unstable -> R.string.eye_page_gaze_unstable
                observation.state == GazeTrackingState.NO_FACE -> R.string.eye_page_no_face
                observation.state == GazeTrackingState.MULTIPLE_FACES -> R.string.eye_page_multiple_faces
                observation.state == GazeTrackingState.EYES_CLOSED -> R.string.eye_page_eyes_closed
                observation.state == GazeTrackingState.TOO_FAR -> R.string.eye_page_too_far
                observation.state != GazeTrackingState.TRACKING -> R.string.eye_page_eyes_unclear
                else -> R.string.eye_page_sampling
            }
            _statusText.value = if (lastIssue == R.string.eye_page_sampling) {
                context.getString(lastIssue, calibration.progress)
            } else {
                context.getString(lastIssue)
            }
            if (point == null) return
            AppLog.put("眼动校准采样完成：stage=${stage()} ${describe(point)}")
            calibration.reset()
            when (_calibrationStage.value) {
                EyeCalibrationStage.TOP -> {
                    topPoint = point
                    _calibrationStage.value = EyeCalibrationStage.BOTTOM
                    beginCalibrationStage()
                }
                EyeCalibrationStage.BOTTOM -> {
                    val top = topPoint ?: return
                    val noise = maxOf(top.noise, point.noise)
                    val samePose = top.hasSimilarPose(point)
                    // 中点通过前，活动 detector 保持未校准。
                    val separated = GazeGestureDetector().calibrate(top.sample.vertical, point.sample.vertical, noise)
                    AppLog.put(
                        "眼动校准比较：top=${describe(top)} bottom=${describe(point)} " +
                            "span=${abs(point.sample.vertical - top.sample.vertical)} " +
                            "requiredSpan=${maxOf(0.008f, noise * 3f)} samePose=$samePose separated=$separated"
                    )
                    if (samePose && separated) {
                        bottomPoint = point
                        _calibrationStage.value = EyeCalibrationStage.MIDPOINT
                        beginCalibrationStage()
                    } else {
                        retryCalibration(if (!samePose) R.string.eye_page_pose_changed else R.string.eye_page_gaze_too_close)
                    }
                }
                EyeCalibrationStage.MIDPOINT -> {
                    val top = topPoint ?: return
                    val bottom = bottomPoint ?: return
                    val profile = GazeCalibrationProfile(
                        top.sample.vertical, bottom.sample.vertical,
                        maxOf(top.noise, bottom.noise), point.sample.vertical, point.noise
                    )
                    val samePose = top.hasSimilarPose(point) && bottom.hasSimilarPose(point)
                    val valid = profile.hasValidMidpoint()
                    AppLog.put(
                        "眼动中点校验：position=${(profile.midpoint - profile.top) / (profile.bottom - profile.top)} " +
                            "noise=${point.noise} samePose=$samePose valid=$valid"
                    )
                    if (samePose && valid) {
                        detector.calibrate(profile.top, profile.bottom, profile.noise)
                        onCalibrationSaved(profile.encode())
                        hideCalibration()
                        detector.reset(time, cooldown = true)
                        context.toastOnUi(R.string.eye_page_ready)
                    } else {
                        retryCalibration(if (!samePose) R.string.eye_page_pose_changed else R.string.eye_page_midpoint_failed)
                    }
                }
            }
            return
        }
        val blocked = readingBlockReason()
        if (blocked != null) {
            hideGazePoint()
            detector.reset(reason = "paused:$blocked")
            updateReadyHint(false)
            recordReading(sample, time, detector.state)
            return
        }
        updateGazePoint(sample.takeIf { observation.state == GazeTrackingState.TRACKING })
        val wasArmed = detector.armed
        val turn = detector.update(sample, time, observation.state == GazeTrackingState.EYES_CLOSED)
        updateReadyHint(detector.armed)
        if (!wasArmed && detector.armed) {
            AppLog.putDebug("眼动翻页已确认底部注视：dwell=${detector.bottomDwellMs}ms position=${sample?.let(detector::position)}")
        }
        recordReading(sample, time, detector.state)
        if (turn) {
            AppLog.put("眼动翻页动作已识别：position=${sample?.let(detector::position)}")
            onNextPage()
        }
    }

    private fun updateReadyHint(visible: Boolean) {
        handler.removeCallbacks(hintTimeout)
        if (visible) handler.postAtTime(hintTimeout, detector.armedUntil)
        if (readyHintVisibleBacking == visible) return
        readyHintVisibleBacking = visible
        _readyHintVisible.value = visible
    }

    private fun recordReading(sample: GazeSample?, time: Long, state: String) {
        readingStates[state] = (readingStates[state] ?: 0) + 1
        sample?.let {
            val position = detector.position(it)
            if (position.isFinite()) {
                minPosition = minOf(minPosition, position)
                maxPosition = maxOf(maxPosition, position)
            }
        }
        if (time - lastReadingLog < 5000L) return
        lastReadingLog = time
        AppLog.putDebug(
            "眼动阅读统计：states=$readingStates positionRange=$minPosition..$maxPosition " +
                "armed=${detector.armed} bottomDwell=${detector.bottomDwellMs} stale=$staleFrames " +
                "pose=${sample?.let { "pitch=${it.headPitch} yaw=${it.headYaw} scale=${it.faceScale}" }}"
        )
        readingStates.clear()
        minPosition = Float.POSITIVE_INFINITY
        maxPosition = Float.NEGATIVE_INFINITY
    }

    private fun updateGazePoint(sample: GazeSample?) {
        if (!gazePointEnabled || sample == null || !detector.calibrated) {
            hideGazePoint()
            return
        }
        val position = detector.position(sample)
        if (!position.isFinite()) {
            hideGazePoint()
            return
        }
        _gazePosition.floatValue = position.coerceIn(0f, 1f)
    }

    private fun hideGazePoint() {
        _gazePosition.floatValue = Float.NaN
    }

    private fun showCalibration() {
        hideGazePoint()
        calibration.reset()
        _calibrationStage.value = EyeCalibrationStage.TOP
        topPoint = null
        bottomPoint = null
        retryCount = 0
        retryHint = null
        staleFrames = 0
        receivedFrames = 0
        lastIssue = R.string.eye_page_camera_starting
        _statusText.value = context.getString(lastIssue)
        _isCalibrating.value = true
        beginCalibrationStage()
    }

    private fun beginCalibrationStage() {
        handler.removeCallbacks(calibrationCountdown)
        handler.removeCallbacks(timeout)
        calibration.reset()
        calibrationPaused = false
        _calibrationPreparing.value = true
        calibrationReadyAt = SystemClock.uptimeMillis() + CALIBRATION_PREPARATION_MS
        updateTarget()
        calibrationCountdown.run()
    }

    private fun pauseCalibration() {
        if (!calibrationPaused) {
            calibration.reset()
            calibrationPaused = true
            _calibrationPreparing.value = true
            handler.removeCallbacks(timeout)
            lastIssue = R.string.eye_page_calibration_paused
            updateTarget()
            _statusText.value = context.getString(lastIssue)
        }
        handler.removeCallbacks(calibrationCountdown)
        handler.postDelayed(calibrationCountdown, 200L)
    }

    private fun updateTarget() {
        val instruction = context.getString(
            when {
                _calibrationPreparing.value && _calibrationStage.value == EyeCalibrationStage.MIDPOINT ->
                    R.string.eye_page_prepare_midpoint
                _calibrationPreparing.value && _calibrationStage.value == EyeCalibrationStage.BOTTOM ->
                    R.string.eye_page_prepare_bottom
                _calibrationPreparing.value -> R.string.eye_page_prepare_top
                _calibrationStage.value == EyeCalibrationStage.MIDPOINT -> R.string.eye_page_calibrate_midpoint
                _calibrationStage.value == EyeCalibrationStage.BOTTOM -> R.string.eye_page_calibrate_bottom
                else -> R.string.eye_page_calibrate_top
            }
        )
        _instructionText.value = instruction + (retryHint?.let { "\n\n$it" } ?: "")
    }

    private fun retryCalibration(reason: Int) {
        lastIssue = reason
        if (retryCount >= 1) {
            onError(
                IllegalStateException(
                    context.getString(
                        R.string.eye_page_calibration_detail, context.getString(reason)
                    )
                )
            )
            return
        }
        retryCount++
        calibration.reset()
        _calibrationStage.value = EyeCalibrationStage.TOP
        topPoint = null
        bottomPoint = null
        // 被拒的校准绝不能留下一个有效的 detector。
        detector.calibrate(0f, 0f)
        retryHint = context.getString(reason)
        beginCalibrationStage()
    }

    private fun stage() = _calibrationStage.value.name.lowercase(Locale.ROOT)

    private fun issueMessage(): String = context.getString(
        if (lastIssue == R.string.eye_page_sampling) R.string.eye_page_gaze_unstable else lastIssue
    )

    private fun describe(point: GazeCalibrationPoint): String = String.format(
        Locale.ROOT,
        "gaze=%.5f noise=%.5f pitch=%.4f yaw=%.4f scale=%.4f count=%d",
        point.sample.vertical, point.noise, point.sample.headPitch, point.sample.headYaw,
        point.sample.faceScale, point.sampleCount
    )

    private fun hideCalibration() {
        handler.removeCallbacks(timeout)
        handler.removeCallbacks(calibrationCountdown)
        _calibrationPreparing.value = false
        _countdownSeconds.intValue = 0
        calibrationPaused = false
        calibrationReadyAt = 0L
        _isCalibrating.value = false
        topPoint = null
        bottomPoint = null
        calibration.reset()
    }

    fun resetGesture() {
        detector.reset(SystemClock.uptimeMillis(), cooldown = true)
        updateReadyHint(false)
    }

    fun stop() {
        generation++
        camera?.stop()
        camera = null
        _running.value = false
        hideCalibration()
        hideGazePoint()
        resetGesture()
    }

    private companion object {
        const val CALIBRATION_PREPARATION_MS = 3000L
        const val CALIBRATION_TIMEOUT_MS = 20000L
        const val MIN_GAZE_POINT_SIZE_DP = 8
        const val MAX_GAZE_POINT_SIZE_DP = 64
    }
}

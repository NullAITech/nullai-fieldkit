package tech.nullai.fieldkit

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import com.meta.wearable.dat.camera.Camera
import com.meta.wearable.dat.camera.Stream
import com.meta.wearable.dat.camera.addCamera
import com.meta.wearable.dat.camera.removeCamera
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamError
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.camera.types.VideoFrame
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.registration.RegistrationRequest
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.selectors.SpecificDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.DeviceIdentifier
import com.meta.wearable.dat.core.types.LinkState
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Meta Wearables DAT bridge (Ray-Ban Meta glasses -> FieldKit Relay).
 *
 * Keeps a long-lived session, awaits DeviceSessionState.STARTED before adding
 * the camera at the official 15fps rate, wakes dormant ACDC link leases, and
 * emits clean JPEG frames directly to [Listener.onJpeg].
 */
object DatGlasses {

    private const val TAG = "DatGlasses"

    interface Listener {
        fun onStatus(text: String)
        fun onJpeg(jpeg: ByteArray)
    }

    @Volatile
    var listener: Listener? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var initialized = false
    @Volatile private var connecting = false
    @Volatile private var streaming = false
    private var appCtx: Context? = null
    private var session: DeviceSession? = null
    private var camera: Camera? = null
    private var stream: Stream? = null
    private var job: Job? = null
    private var stateJob: Job? = null
    private var errJob: Job? = null
    private var sessErrJob: Job? = null
    private var registrationJob: Job? = null

    @Volatile
    private var frameCount = 0

    private val permContract = Wearables.RequestPermissionContract()

    // --- DAT registration plumbing -------------------------------------------
    // Meta AI hands registration to the app as a single-use RegistrationRequest
    // delivered through an Android intent. It must be continued from a live
    // Activity, so the Activity is parked here between resume and pause.
    @Volatile private var currentActivity: Activity? = null
    @Volatile private var pendingRequest: RegistrationRequest? = null

    fun attachActivity(activity: Activity) {
        currentActivity = activity
        val req = pendingRequest
        if (req != null) {
            pendingRequest = null
            continueRegistration(req, activity, "resumed")
        }
    }

    fun detachActivity(activity: Activity) {
        if (currentActivity === activity) currentActivity = null
    }

    private fun continueRegistration(req: RegistrationRequest, activity: Activity, from: String) {
        try {
            req.continueRegistration(activity)
                .onSuccess { Log.i(TAG, "continueRegistration($from) OK flow=${req.flowId}") }
                .onFailure { e, _ -> Log.e(TAG, "continueRegistration($from) failed: ${e.description}") }
        } catch (t: Throwable) {
            Log.e(TAG, "continueRegistration($from) exception", t)
        }
    }

    fun init(context: Context) {
        val c = context.applicationContext
        appCtx = c
        try {
            com.facebook.debug.log.BLog.setLogLevel(Log.VERBOSE)
        } catch (_: Throwable) {}
        if (!initialized) {
            try {
                val r = Wearables.initialize(c)
                initialized = r.isSuccess
                r.onFailure { e, _ -> Log.e(TAG, "Wearables.initialize failed: ${e.description}") }
                Log.i(TAG, "Wearables.initialize -> $initialized (devMode=${Wearables.isDevMode})")
            } catch (t: Throwable) {
                Log.e(TAG, "Wearables.initialize exception", t)
            }
        }
        // registrationState is the gate for sessions; log every transition so a
        // rejected/never-finished approval is visible in logcat instead of silent.
        if (initialized && registrationJob == null) {
            registrationJob = scope.launch {
                try {
                    Wearables.registrationState.collect { st -> Log.i(TAG, "registrationState -> $st") }
                } catch (t: Throwable) {
                    Log.e(TAG, "registrationState collect failed", t)
                }
            }
        }
    }

    fun handleIntent(intent: Intent?) {
        if (!initialized || intent == null) return
        try {
            // The callback receives a RegistrationRequest, NOT an error. Dropping
            // it (previous behaviour) kills the flow: DAT then reports
            // "No wearable devices have been discovered or registered".
            Wearables.handleIntent(intent) { request ->
                Log.i(TAG, "RegistrationRequest flow=${request.flowId} proto=${request.protocolVersion}")
                val act = currentActivity
                if (act != null) {
                    continueRegistration(request, act, "live")
                } else {
                    pendingRequest = request
                    Log.w(TAG, "No Activity attached - request parked until next resume")
                }
            }.onFailure { e, _ ->
                Log.i(TAG, "handleIntent: not a registration intent (${e.description})")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "handleIntent exception", t)
        }
    }

    fun registrationState(): String = try {
        Wearables.registrationState.value.toString()
    } catch (_: Throwable) {
        "UNKNOWN"
    }

    fun register(activity: Activity) {
        if (!initialized) return
        try {
            currentActivity = activity
            Log.i(TAG, "startRegistration (devMode=${Wearables.isDevMode} reg=${registrationState()})")
            Wearables.startRegistration(activity)
        } catch (t: Throwable) {
            listener?.onStatus("Open Meta AI app to register glasses: ${t.message}")
        }
    }

    fun isRegistered(): Boolean {
        if (!initialized) return false
        return try {
            val s = Wearables.registrationState.value.toString()
            s.contains("Registered", ignoreCase = true) && !s.contains("Unregistered", ignoreCase = true)
        } catch (_: Throwable) {
            false
        }
    }

    fun isStreaming(): Boolean = streaming && stream != null && session != null

    fun isConnecting(): Boolean = connecting

    fun createCameraPermissionIntent(context: Context): Intent {
        return permContract.createIntent(context, Permission.CAMERA)
    }

    fun parseCameraPermissionResult(resultCode: Int, data: Intent?): Boolean {
        return try {
            val res = permContract.parseResult(resultCode, data)
            val st = res.getOrNull()
            Log.i(TAG, "parseCameraPermissionResult -> $st (err=${res.errorOrNull()?.description})")
            st == PermissionStatus.Granted
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Checks if DAT camera permission is granted. Only returns false when
     * Wearables.checkPermissionStatus explicitly returns PermissionStatus.Denied.
     * When the glasses link is still establishing (status == null), returns true
     * so we connect directly without bouncing to Meta AI.
     */
    fun checkCameraPermission(onResult: java.util.function.Consumer<Boolean>) {
        if (!initialized) {
            onResult.accept(true)
            return
        }
        scope.launch {
            val allowDirectConnect = try {
                val res = Wearables.checkPermissionStatus(Permission.CAMERA)
                val status = res.getOrNull()
                Log.i(TAG, "checkPermissionStatus(CAMERA) -> $status (err=${res.errorOrNull()?.description})")
                status != PermissionStatus.Denied
            } catch (_: Throwable) {
                true
            }
            onResult.accept(allowDirectConnect)
        }
    }

    /** What the SDK itself sees — named in the status line so failures explain themselves. */
    fun diagnostics(): String {
        val ids = try { Wearables.devices.value } catch (_: Throwable) { emptySet() }
        val devMode = try { Wearables.isDevMode } catch (_: Throwable) { false }
        val parts = StringBuilder(
            "reg=${registrationState()} devMode=$devMode devices=${ids.size}"
        )
        // mwdat 1.0.1 gates: Meta AI app V292+ and glasses firmware V128+.
        parts.append(" sdk=1.0.1 needs[metaAI>=V292 fw>=V128]")
        for (id in ids) {
            val d = try { Wearables.devicesMetadata[id]?.value } catch (_: Throwable) { null }
            if (d != null) {
                parts.append(" [${d.name} link=${d.linkState} compat=${d.compatibility}" +
                    " don=${d.donState} hinge=${d.hingeState} batt=${d.batteryLevel}%]")
            } else {
                parts.append(" [$id]")
            }
        }
        return parts.toString()
    }

    private fun nudgeDisconnectedDevices() {
        try {
            val wearablesImplField = Wearables::class.java.getDeclaredField("instance")
            wearablesImplField.isAccessible = true
            val wearablesImpl = wearablesImplField.get(Wearables) ?: return
            val devMgrField = wearablesImpl.javaClass.getDeclaredField("deviceManager")
            devMgrField.isAccessible = true
            val devMgr = devMgrField.get(wearablesImpl) ?: return

            val linkedDevicesField = devMgr.javaClass.getDeclaredField("linkedDevices")
            linkedDevicesField.isAccessible = true
            val linkedMap = linkedDevicesField.get(devMgr) as? Map<*, *> ?: return

            val stopMethod = devMgr.javaClass.getDeclaredMethods().firstOrNull { it.name == "stopMonitoringDevice" }
            val startMethod = devMgr.javaClass.getDeclaredMethods().firstOrNull { it.name == "startMonitoringDevice" }
            stopMethod?.isAccessible = true
            startMethod?.isAccessible = true

            val metaMap = try { Wearables.devicesMetadata } catch (_: Throwable) { emptyMap() }
            for ((k, linkedDev) in linkedMap.entries) {
                val devId = k as? DeviceIdentifier ?: continue
                if (linkedDev == null) continue
                val curLink = try { metaMap[devId]?.value?.linkState } catch (_: Throwable) { null }
                if (curLink != LinkState.CONNECTED) {
                    Log.i(TAG, "Nudging ACDC link lease for $devId (curLink=$curLink)")
                    stopMethod?.invoke(devMgr, devId)
                    startMethod?.invoke(devMgr, devId, linkedDev)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "nudgeDisconnectedDevices note: ${t.message}")
        }
    }

    fun connect() {
        if (!initialized) {
            listener?.onStatus("Glasses SDK not initialized.")
            return
        }
        if (isStreaming()) {
            listener?.onStatus("Glasses streaming — frames are flowing.")
            return
        }
        if (connecting && job?.isActive == true) return

        connecting = true
        frameCount = 0
        job?.cancel()
        closeSessionInternal()

        job = scope.launch {
            try {
                listener?.onStatus("Connecting to Meta Ray-Ban glasses…")

                // Wait up to 9 seconds for ACDC device discovery and LinkState.CONNECTED
                var waitMs = 0L
                while (waitMs < 9000L) {
                    val devs = try { Wearables.devices.value } catch (_: Throwable) { emptySet() }
                    val metaMap = try { Wearables.devicesMetadata } catch (_: Throwable) { emptyMap() }
                    val hasConnected = metaMap.values.any { flow ->
                        try { flow.value.linkState == LinkState.CONNECTED } catch (_: Throwable) { false }
                    }
                    if (devs.isNotEmpty() && hasConnected) break
                    if (waitMs == 750L || waitMs == 3000L || waitMs == 6000L) {
                        nudgeDisconnectedDevices()
                    }
                    if (waitMs == 1000L || waitMs == 4000L || waitMs == 7000L) {
                        listener?.onStatus("Waiting for glasses link (${waitMs / 1000}s)… ${diagnostics()}")
                    }
                    delay(250)
                    waitMs += 250
                }

                listener?.onStatus("Opening Meta glasses session… ${diagnostics()}")

                var sess: DeviceSession? = null
                var lastErrDesc = "no session"
                val created = Wearables.createSession(AutoDeviceSelector())
                sess = created.getOrNull()
                if (sess == null) {
                    lastErrDesc = created.errorOrNull()?.description ?: "AutoDeviceSelector failed"
                    val candidateIds = LinkedHashSet<DeviceIdentifier>()
                    try { candidateIds.addAll(Wearables.devices.value) } catch (_: Throwable) {}
                    try { candidateIds.addAll(Wearables.devicesMetadata.keys) } catch (_: Throwable) {}
                    for (devId in candidateIds) {
                        val specRes = Wearables.createSession(SpecificDeviceSelector(devId))
                        val s = specRes.getOrNull()
                        if (s != null) {
                            sess = s
                            break
                        } else {
                            lastErrDesc = specRes.errorOrNull()?.description ?: lastErrDesc
                        }
                    }
                }

                if (sess == null) {
                    listener?.onStatus("No eligible glasses device: $lastErrDesc (${diagnostics()})")
                    return@launch
                }

                session = sess
                var lastSessionErr = "none"
                sessErrJob?.cancel()
                sessErrJob = scope.launch {
                    try {
                        sess.errors.collect { err ->
                            lastSessionErr = err.description
                            Log.w(TAG, "DeviceSession.errors -> ${err.name}: ${err.description}")
                            // DAT_APP_ON_THE_GLASSES_UPDATE_REQUIRED is the commonest
                            // field failure on Ray-Ban Meta gen 1: the glasses themselves
                            // carry a DAT app that must be updated before a session is allowed.
                            listener?.onStatus(
                                if (err.name.contains("UPDATE_REQUIRED"))
                                    "Your glasses need an update: Meta AI \u2192 App Connections \u2192 Spotter \u2192 Update, then tap Connect glasses again."
                                else "Glasses session notice: ${err.description}")
                        }
                    } catch (_: Throwable) {}
                }

                Log.i(TAG, "Starting DeviceSession (devMode=${Wearables.isDevMode})")
                sess.start()

                // Wait up to 8 seconds for DeviceSessionState.STARTED before calling addCamera
                var sessWaitMs = 0L
                while (sessWaitMs < 8000L && sess.state.value != DeviceSessionState.STARTED) {
                    val curState = sess.state.value
                    if (curState == DeviceSessionState.STOPPED || curState == DeviceSessionState.STOPPING) {
                        break
                    }
                    delay(120)
                    sessWaitMs += 120
                }
                Log.i(TAG, "DeviceSession state after ${sessWaitMs}ms -> ${sess.state.value} (err=$lastSessionErr)")

                if (sess.state.value != DeviceSessionState.STARTED) {
                    listener?.onStatus("Glasses session=${sess.state.value} ($lastSessionErr). Keep glasses unfolded and retry.")
                    return@launch
                }

                // 15 fps is officially supported in VideoFormat.SUPPPORTED_FRAME_RATES = [30, 24, 15, 7, 2]
                val camResult = sess.addCamera(
                    StreamConfiguration(frameRate = 15, videoQuality = VideoQuality.MEDIUM)
                )
                val cam = camResult.getOrNull()
                if (cam == null) {
                    listener?.onStatus("Glasses camera unavailable: ${camResult.errorOrNull()?.description}")
                    sess.stop()
                    return@launch
                }
                camera = cam
                val str = cam.stream
                stream = str
                listener?.onStatus("Glasses session started. Waiting for first video frame…")

                stateJob?.cancel()
                stateJob = scope.launch {
                    try {
                        str.state.collectLatest { st: StreamState ->
                            Log.i(TAG, "StreamState -> $st")
                            streaming = (st == StreamState.STREAMING || st == StreamState.STARTING)
                        }
                    } catch (_: Throwable) {}
                }

                errJob?.cancel()
                errJob = scope.launch {
                    try {
                        str.errorStream.collectLatest { err: StreamError ->
                            Log.w(TAG, "StreamError -> $err")
                            listener?.onStatus("Glasses stream error: $err")
                        }
                    } catch (_: Throwable) {}
                }

                val started = str.start()
                if (started.getOrNull() == null && started.errorOrNull() != null) {
                    listener?.onStatus("Glasses stream did not start: ${started.errorOrNull()?.description}")
                    return@launch
                }
                streaming = true

                var lastSent = 0L
                var formatWarned = false
                str.videoStream.collect { frame ->
                    if (!frame.isCodecConfig) {
                        val now = System.currentTimeMillis()
                        if (now - lastSent >= 120) {
                            val jpeg = toJpeg(frame)
                            if (jpeg != null) {
                                lastSent = now
                                frameCount += 1
                                streaming = true
                                if (frameCount == 1) {
                                    Log.i(TAG, "First glasses video frame decoded (${frame.width}x${frame.height}, ${jpeg.size} bytes)")
                                    listener?.onStatus("Glasses streaming — frames are flowing (${frame.width}×${frame.height}).")
                                }
                                listener?.onJpeg(jpeg)
                            } else if (!formatWarned) {
                                formatWarned = true
                                listener?.onStatus(
                                    "Glasses frames arrived, but no readable image could be decoded yet."
                                )
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Glasses connection failed", e)
                listener?.onStatus("Glasses connection failed: ${e.javaClass.simpleName}")
            } finally {
                connecting = false
            }
        }
    }

    private fun closeSessionInternal() {
        streaming = false
        stateJob?.cancel(); stateJob = null
        errJob?.cancel(); errJob = null
        sessErrJob?.cancel(); sessErrJob = null
        try { stream?.stop() } catch (_: Exception) {}
        try { session?.removeCamera() } catch (_: Exception) {}
        try { session?.stop() } catch (_: Exception) {}
        stream = null
        camera = null
        session = null
    }

    fun disconnect() {
        connecting = false
        job?.cancel()
        job = null
        closeSessionInternal()
        frameCount = 0
    }

    private fun toJpeg(frame: VideoFrame): ByteArray? {
        if (frame.isCompressed) return null
        val width = frame.width
        val height = frame.height
        if (width <= 0 || height <= 0) return null
        val duplicate = frame.buffer.duplicate()
        duplicate.rewind()
        val bytes = ByteArray(duplicate.remaining())
        duplicate.get(bytes)
        val rgbaBytes = width * height * 4
        val yuvBytes = width * height * 3 / 2
        val rgbBytes = width * height * 3
        return when {
            bytes.size >= rgbaBytes -> {
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(bytes, 0, rgbaBytes))
                ByteArrayOutputStream().also {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 78, it)
                }.toByteArray()
            }

            bytes.size >= yuvBytes -> {
                // DAT decoded output uses Android color format 19 (planar YUV 420 / I420):
                // Y plane, then U plane, then V plane. Convert to NV21 for JPEG.
                val nv21 = ByteArray(yuvBytes)
                System.arraycopy(bytes, 0, nv21, 0, width * height)
                val uOffset = width * height
                val vOffset = uOffset + width * height / 4
                var p = width * height
                for (i in 0 until width * height / 4) {
                    if (vOffset + i >= bytes.size || uOffset + i >= bytes.size) break
                    nv21[p++] = bytes[vOffset + i]
                    nv21[p++] = bytes[uOffset + i]
                }
                val out = ByteArrayOutputStream()
                val ok = YuvImage(nv21, ImageFormat.NV21, width, height, null)
                    .compressToJpeg(Rect(0, 0, width, height), 78, out)
                if (ok) out.toByteArray() else null
            }

            bytes.size >= rgbBytes -> {
                val pixels = IntArray(width * height)
                for (i in pixels.indices) {
                    val o = i * 3
                    pixels[i] = (0xFF shl 24) or ((bytes[o].toInt() and 0xFF) shl 16) or
                        ((bytes[o + 1].toInt() and 0xFF) shl 8) or (bytes[o + 2].toInt() and 0xFF)
                }
                val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
                ByteArrayOutputStream().also {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 78, it)
                }.toByteArray()
            }

            else -> null
        }
    }
}

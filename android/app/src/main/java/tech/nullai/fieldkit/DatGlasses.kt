package tech.nullai.fieldkit

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import com.meta.wearable.dat.camera.Camera
import com.meta.wearable.dat.camera.Stream
import com.meta.wearable.dat.camera.addCamera
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.camera.types.VideoFrame
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.types.RegistrationState
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Meta Wearables DAT bridge (Ray-Ban Meta glasses -> FieldKit Relay).
 *
 * Follows the same shape as the original Zoth Guide path: one long-lived
 * session, a continuous newest-frame stream, and JPEG frames forwarded to the
 * relay. It does NOT call the glasses connected until a real video frame has
 * been decoded and handed to the listener.
 */
object DatGlasses {

    interface Listener {
        fun onStatus(text: String)
        fun onJpeg(jpeg: ByteArray)
    }

    @Volatile
    var listener: Listener? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var session: DeviceSession? = null
    private var camera: Camera? = null
    private var stream: Stream? = null
    private var job: Job? = null

    @Volatile
    private var frameCount = 0

    fun init(context: Context) {
        Wearables.initialize(context.applicationContext)
    }

    fun registrationState(): String = Wearables.registrationState.value.name

    fun register(activity: Activity) {
        Wearables.startRegistration(activity)
    }

    fun isRegistered(): Boolean =
        Wearables.registrationState.value == RegistrationState.REGISTERED

    fun connect() {
        if (job?.isActive == true) return
        frameCount = 0
        job = scope.launch {
            if (!isRegistered()) {
                listener?.onStatus(
                    "Glasses not registered (${registrationState()}). " +
                        "Tap Connect again to register in the Meta AI app."
                )
                return@launch
            }
            try {
                listener?.onStatus("Opening Meta glasses session…")
                val created = Wearables.createSession(AutoDeviceSelector())
                val sess = created.getOrNull()
                if (sess == null) {
                    listener?.onStatus("No eligible glasses device: ${created.errorOrNull()}")
                    return@launch
                }
                session = sess
                sess.start()

                val camResult = sess.addCamera(
                    StreamConfiguration(frameRate = 12, videoQuality = VideoQuality.MEDIUM)
                )
                val cam = camResult.getOrNull()
                if (cam == null) {
                    listener?.onStatus("Glasses camera unavailable: ${camResult.errorOrNull()}")
                    sess.stop()
                    return@launch
                }
                camera = cam
                val str = cam.stream
                stream = str
                listener?.onStatus("Glasses session started. Waiting for first video frame…")

                launch {
                    str.state.collect { state ->
                        if (state == StreamState.STREAMING && frameCount == 0) {
                            listener?.onStatus("Glasses stream started. Waiting for first video frame…")
                        }
                    }
                }
                launch {
                    str.errorStream.collect { err ->
                        listener?.onStatus("Glasses stream error: $err")
                    }
                }
                launch {
                    sess.errors.collect { err ->
                        listener?.onStatus("Glasses session error: $err")
                    }
                }

                val started = str.start()
                if (started.getOrNull() == null) {
                    listener?.onStatus("Glasses stream did not start: ${started.errorOrNull()}")
                    return@launch
                }

                var lastSent = 0L
                var formatWarned = false
                str.videoStream.collect { frame ->
                    if (!frame.isCodecConfig) {
                        val now = System.currentTimeMillis()
                        if (now - lastSent >= 500) {
                            val jpeg = toJpeg(frame)
                            if (jpeg != null) {
                                lastSent = now
                                frameCount += 1
                                if (frameCount == 1) {
                                    listener?.onStatus("Glasses streaming — frames are flowing.")
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
                listener?.onStatus("Glasses connection failed: ${e.javaClass.simpleName}")
            }
        }
    }

    fun disconnect() {
        job?.cancel()
        job = null
        try {
            stream?.stop()
        } catch (e: Exception) {
        }
        try {
            camera?.stop()
        } catch (e: Exception) {
        }
        try {
            session?.stop()
        } catch (e: Exception) {
        }
        stream = null
        camera = null
        session = null
        frameCount = 0
    }

    private fun toJpeg(frame: VideoFrame): ByteArray? {
        if (frame.isCompressed) return null
        val width = frame.width
        val height = frame.height
        if (width <= 0 || height <= 0) return null
        val duplicate = frame.buffer.duplicate()
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
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 70, it)
                }.toByteArray()
            }

            bytes.size >= yuvBytes -> {
                // DAT decoded output uses Android color format 19 (planar YUV 420):
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
                    .compressToJpeg(Rect(0, 0, width, height), 70, out)
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
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 70, it)
                }.toByteArray()
            }

            else -> null
        }
    }
}

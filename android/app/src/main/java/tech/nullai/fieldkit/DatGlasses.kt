package tech.nullai.fieldkit

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.meta.wearable.dat.camera.Camera
import com.meta.wearable.dat.camera.addCamera
import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.types.RegistrationState
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Meta Wearables DAT bridge (Ray-Ban Meta glasses -> FieldKit Relay).
 *
 * Glasses are managed by the Meta AI app; this bridge registers with it
 * (Wearables Developer Center APPLICATION_ID in the manifest), opens a DAT
 * session, and forwards glasses photos as relay frames — the same frame path
 * the phone camera uses. Requires Developer Mode for the glasses in Meta AI.
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
    private var job: Job? = null

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
        job = scope.launch {
            if (!isRegistered()) {
                listener?.onStatus(
                    "Glasses not registered (${registrationState()}). " +
                        "Tap Connect again to register in the Meta AI app."
                )
                return@launch
            }
            val created = Wearables.createSession(AutoDeviceSelector())
            val sess = created.getOrNull()
            if (sess == null) {
                listener?.onStatus("Could not start a glasses session: ${created.errorOrNull()}")
                return@launch
            }
            session = sess
            sess.start()
            val camResult = sess.addCamera(StreamConfiguration())
            val cam = camResult.getOrNull()
            if (cam == null) {
                listener?.onStatus("Glasses camera unavailable: ${camResult.errorOrNull()}")
                return@launch
            }
            camera = cam
            listener?.onStatus("Glasses connected. Sending what they see to the relay.")
            while (isActive) {
                val data = cam.stream.capturePhoto().getOrNull()
                if (data != null) {
                    toJpeg(data)?.let { listener?.onJpeg(it) }
                }
                delay(2000)
            }
        }
    }

    fun disconnect() {
        job?.cancel()
        job = null
        try {
            camera?.stop()
        } catch (e: Exception) {
        }
        try {
            session?.stop()
        } catch (e: Exception) {
        }
        camera = null
        session = null
    }

    private fun toJpeg(data: PhotoData): ByteArray? = when (data) {
        is PhotoData.Bitmap -> ByteArrayOutputStream().also {
            data.bitmap.compress(Bitmap.CompressFormat.JPEG, 70, it)
        }.toByteArray()

        is PhotoData.HEIC -> {
            val raw = ByteArray(data.data.remaining()).also { data.data.duplicate().get(it) }
            BitmapFactory.decodeByteArray(raw, 0, raw.size)?.let { bmp ->
                ByteArrayOutputStream().also {
                    bmp.compress(Bitmap.CompressFormat.JPEG, 70, it)
                }.toByteArray()
            }
        }

        else -> null
    }
}

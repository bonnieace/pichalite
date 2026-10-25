package ke.co.pichalite

import android.app.Application
import com.posthog.PostHog
import com.posthog.android.PostHogAndroid
import com.posthog.android.PostHogAndroidConfig

/** Anonymous app metadata only. Never submit photo content, paths, names or exception messages. */
object PichaAnalytics {
    @Volatile private var configured = false

    fun initialize(app: Application) {
        if (BuildConfig.POSTHOG_KEY.isBlank()) return
        val config = PostHogAndroidConfig(
            apiKey = BuildConfig.POSTHOG_KEY,
            host = BuildConfig.POSTHOG_HOST
        ).apply {
            optOut = false
            captureApplicationLifecycleEvents = true
            captureScreenViews = false
            captureDeepLinks = false
            sessionReplay = false
            errorTrackingConfig.autoCapture = false
            preloadFeatureFlags = false
        }
        runCatching {
            PostHogAndroid.setup(app, config)
            // Clear the SDK's saved opt-out from older previews with the switch.
            PostHog.optIn()
        }.onSuccess { configured = true }
    }

    fun capture(name: String, properties: Map<String, Any> = emptyMap()) {
        if (configured) runCatching { PostHog.capture(event = name, properties = properties) }
    }
}

class PichaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        PichaAnalytics.initialize(this)
    }
}

package ke.co.pichalite

import android.app.Application
import android.content.Context
import com.posthog.PostHog
import com.posthog.android.PostHogAndroid
import com.posthog.android.PostHogAndroidConfig

/** Anonymous, opt-in event capture. Never submit photo content, paths, names or exception messages. */
object PichaAnalytics {
    private const val PREFS = "privacy"
    private const val CONSENT = "analytics_enabled"
    private var configured = false
    private var enabled = false

    fun enabled(context: Context): Boolean =
        BuildConfig.POSTHOG_KEY.isNotBlank() &&
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(CONSENT, false)

    fun initialize(app: Application) {
        if (BuildConfig.POSTHOG_KEY.isBlank()) return
        enabled = enabled(app)
        val config = PostHogAndroidConfig(
            apiKey = BuildConfig.POSTHOG_KEY,
            host = BuildConfig.POSTHOG_HOST
        ).apply {
            optOut = !enabled
            captureApplicationLifecycleEvents = false
            captureScreenViews = false
            captureDeepLinks = false
            sessionReplay = false
            errorTrackingConfig.autoCapture = false
            preloadFeatureFlags = false
        }
        PostHogAndroid.setup(app, config)
        configured = true
    }

    fun setConsent(context: Context, consent: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(CONSENT, consent).apply()
        enabled = consent && configured
        if (!configured) return
        if (enabled) PostHog.optIn() else PostHog.optOut()
    }

    fun capture(name: String, properties: Map<String, Any> = emptyMap()) {
        if (configured && enabled) PostHog.capture(event = name, properties = properties)
    }
}

class PichaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        PichaAnalytics.initialize(this)
    }
}

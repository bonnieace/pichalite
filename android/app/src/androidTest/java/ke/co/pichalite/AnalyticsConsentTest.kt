package ke.co.pichalite

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.posthog.PostHog
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AnalyticsConsentTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun configuredProjectUsesOnlyPublicKeyAndStartsOptedOut() {
        PichaAnalytics.setConsent(context, false)
        assertTrue(BuildConfig.POSTHOG_KEY.startsWith("phc_"))
        assertFalse(PichaAnalytics.enabled(context))
        assertTrue(PostHog.isOptOut())
    }

    @Test fun explicitConsentCanBeEnabledAndRevoked() {
        try {
            PichaAnalytics.setConsent(context, true)
            assertTrue(PichaAnalytics.enabled(context))
            assertFalse(PostHog.isOptOut())
        } finally {
            PichaAnalytics.setConsent(context, false)
        }
        assertFalse(PichaAnalytics.enabled(context))
        assertTrue(PostHog.isOptOut())
    }

    @Test fun captureWhileOptedOutLeavesSdkDisabled() {
        PichaAnalytics.setConsent(context, false)
        PichaAnalytics.capture("analytics_consent_test", mapOf("test" to true))
        assertTrue(PostHog.isOptOut())
        assertFalse(PichaAnalytics.enabled(context))
    }
}

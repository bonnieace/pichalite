package ke.co.pichalite

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.posthog.PostHog
import org.junit.Assert.*
import org.junit.Test
import org.junit.FixMethodOrder
import org.junit.runners.MethodSorters
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class AnalyticsConsentTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun aConfiguredProjectStartsEnabledByDefault() {
        context.getSharedPreferences("privacy", 0).edit().remove("analytics_enabled").commit()
        assertTrue(BuildConfig.POSTHOG_KEY.startsWith("phc_"))
        assertTrue(PichaAnalytics.enabled(context))
        // No test has toggled SDK consent yet: verify real Application startup.
        assertFalse(PostHog.isOptOut())
    }

    @Test fun bExplicitConsentCanBeEnabledAndRevoked() {
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

    @Test fun cCaptureWhileOptedOutLeavesSdkDisabled() {
        PichaAnalytics.setConsent(context, false)
        PichaAnalytics.capture("analytics_consent_test", mapOf("test" to true))
        assertTrue(PostHog.isOptOut())
        assertFalse(PichaAnalytics.enabled(context))
    }
}

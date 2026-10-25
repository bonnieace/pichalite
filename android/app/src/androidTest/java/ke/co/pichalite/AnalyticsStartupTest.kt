package ke.co.pichalite

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.posthog.PostHog
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AnalyticsStartupTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun configuredApplicationStartsAnalyticsWithoutUserAction() {
        assertTrue(BuildConfig.POSTHOG_KEY.startsWith("phc_"))
        assertFalse(PostHog.isOptOut())
    }

    @Test fun oldOptOutSettingDoesNotDisableThisBuild() {
        context.getSharedPreferences("privacy", 0).edit().putBoolean("analytics_enabled", false).commit()
        try {
            PostHog.optOut()
            assertTrue(PostHog.isOptOut())
            PichaAnalytics.initialize(context.applicationContext as Application)
            assertFalse(PostHog.isOptOut())
        } finally {
            context.getSharedPreferences("privacy", 0).edit().remove("analytics_enabled").commit()
        }
    }
}

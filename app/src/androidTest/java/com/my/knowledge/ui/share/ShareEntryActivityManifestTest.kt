package com.my.knowledge.ui.share

import android.content.ComponentName
import android.content.pm.ActivityInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShareEntryActivityManifestTest {

    @Test
    fun shareEntryDoesNotJoinOrPersistInTheMainAppTask() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val activityInfo = context.packageManager.getActivityInfo(
            ComponentName(context, ShareEntryActivity::class.java),
            0
        )

        assertEquals(ActivityInfo.LAUNCH_MULTIPLE, activityInfo.launchMode)
        assertNull(activityInfo.taskAffinity)
        assertTrue(activityInfo.flags and ActivityInfo.FLAG_NO_HISTORY != 0)
        assertTrue(activityInfo.flags and ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS != 0)
    }
}

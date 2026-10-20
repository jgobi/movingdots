package io.github.jgobi.movingdots

import android.app.ActivityManager
import android.content.Context

/**
 * Keeps the app out of recent apps while the dots are on. On Infinix XOS (and similar OEM systems)
 * swiping the app's card up kills the whole process, overlay included, and nothing restarts it;
 * with the task excluded from recents, the swipe no longer triggers that cleanup. While the dots
 * are off the app shows in recents as usual, since a kill then loses nothing.
 */
object RecentsVisibility {
    fun update(context: Context, dotsOn: Boolean = MotionCueService.isRunning) {
        for (task in context.getSystemService(ActivityManager::class.java).appTasks) {
            runCatching { task.setExcludeFromRecents(dotsOn) }
        }
    }
}

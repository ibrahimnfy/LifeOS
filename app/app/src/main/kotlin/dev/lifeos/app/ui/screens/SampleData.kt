package dev.lifeos.app.ui.screens

import androidx.annotation.StringRes
import dev.lifeos.app.R

/**
 * Invented placeholder tasks, the same as in the design mockups. They exist only
 * so the skeleton shows the real layout; nothing here is stored or synced.
 */
data class SampleTask(
    @param:StringRes val title: Int,
    @param:StringRes val subtitle: Int,
    @param:StringRes val reason: Int? = null,
    val overdue: Boolean = false,
    val done: Boolean = false,
)

object SampleData {
    val topThree = listOf(
        SampleTask(R.string.sample_task_1, R.string.sample_task_1_sub, R.string.sample_task_1_why),
        SampleTask(R.string.sample_task_2, R.string.sample_task_2_sub, R.string.sample_task_2_why, overdue = true),
        SampleTask(R.string.sample_task_3, R.string.sample_task_3_sub, R.string.sample_task_3_why),
    )

    val today = listOf(
        SampleTask(R.string.sample_task_1, R.string.sample_task_1_sub),
        SampleTask(R.string.sample_task_4, R.string.sample_task_4_sub),
        SampleTask(R.string.sample_task_5, R.string.sample_task_5_sub, done = true),
    )

    val mostImportant = listOf(
        SampleTask(R.string.sample_task_1, R.string.sample_task_1_sub),
        SampleTask(R.string.sample_task_2, R.string.sample_task_2_sub, overdue = true),
        SampleTask(R.string.sample_task_3, R.string.sample_task_3_sub),
        SampleTask(R.string.sample_task_6, R.string.sample_task_6_sub),
        SampleTask(R.string.sample_task_7, R.string.sample_task_7_sub),
    )

    /** The sample size of the full task list behind "Show all". */
    const val OPEN_TASK_COUNT = 23
}

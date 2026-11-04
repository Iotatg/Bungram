package app.bungram.core.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

object TimeFormat {

    private val clock = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val clock12 = SimpleDateFormat("h:mm a", Locale.getDefault())
    private val dayMonth = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
    private val monthDay = SimpleDateFormat("MMM d", Locale.getDefault())

    fun messageTime(epochMs: Long): String = synchronized(clock) { clock.format(Date(epochMs)) }

    fun chatRowTime(epochMs: Long, now: Long = System.currentTimeMillis()): String {
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        val startOfToday = cal.clone() as Calendar
        startOfToday.set(Calendar.HOUR_OF_DAY, 0)
        startOfToday.set(Calendar.MINUTE, 0)
        startOfToday.set(Calendar.SECOND, 0)
        startOfToday.set(Calendar.MILLISECOND, 0)

        val diff = now - epochMs
        return when {
            epochMs >= startOfToday.timeInMillis -> synchronized(clock12) { clock12.format(Date(epochMs)) }
            diff < TimeUnit.DAYS.toMillis(2) -> "Yesterday"
            else -> synchronized(dayMonth) { dayMonth.format(Date(epochMs)) }
        }
    }

    fun dayLabel(epochMs: Long): String {
        val cal = Calendar.getInstance().apply { timeInMillis = epochMs }
        val now = Calendar.getInstance()
        val sameDay = cal.get(Calendar.YEAR) == now.get(Calendar.YEAR) &&
            cal.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)
        val prefix = if (sameDay) "Today" else synchronized(monthDay) { monthDay.format(Date(epochMs)) }
        return "$prefix, ${synchronized(monthDay) { monthDay.format(Date(epochMs)) }}"
    }

    fun duration(sec: Int): String {
        val m = sec / 60
        val s = sec % 60
        return if (m >= 60) "%d:%02d:%02d".format(m / 60, m % 60, s) else "%d:%02d".format(m, s)
    }

    fun fileSize(bytes: Long): String = when {
        bytes <= 0 -> ""
        bytes < 1024 -> "$bytes b"
        bytes < 1024 * 1024 -> "${bytes / 1024} kb"
        else -> String.format(Locale.US, "%.1f mb", bytes / 1024f / 1024f)
    }
}

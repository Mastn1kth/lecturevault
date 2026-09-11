package app.lecturevault.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

object Formatters {
    fun duration(milliseconds: Long): String {
        val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(milliseconds.coerceAtLeast(0))
        val hours = totalSeconds / 3_600
        val minutes = (totalSeconds % 3_600) / 60
        val seconds = totalSeconds % 60
        return "%02d:%02d:%02d".format(Locale.ROOT, hours, minutes, seconds)
    }

    fun dateTime(timestamp: Long): String =
        SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("ru", "RU")).format(Date(timestamp))

    fun defaultLectureTitle(timestamp: Long = System.currentTimeMillis()): String =
        "Лекция ${SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("ru", "RU")).format(Date(timestamp))}"
}

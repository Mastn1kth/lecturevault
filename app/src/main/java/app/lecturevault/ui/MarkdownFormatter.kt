package app.lecturevault.ui

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.View
import androidx.core.content.ContextCompat
import app.lecturevault.R

object MarkdownFormatter {
    private val timestamp = Regex("\\[(\\d{1,2}:\\d{2}(?::\\d{2})?)(?:\\s*[–-]\\s*\\d{1,2}:\\d{2}(?::\\d{2})?)?]")

    fun format(
        context: Context,
        markdown: String,
        onTimestampClick: ((TimestampReference) -> Unit)? = null,
    ): CharSequence {
        val body = stripFrontMatter(markdown)
        val result = SpannableStringBuilder()
        var currentPart: Int? = null
        body.lineSequence().forEach { source ->
            val line = source.trimEnd()
            TimestampReferenceParser.partNumber(line)?.let { currentPart = it }
            val heading = line.takeWhile { it == '#' }.length.takeIf { it in 1..3 && line.getOrNull(it) == ' ' }
            val text = when {
                heading != null -> line.drop(heading + 1)
                line.startsWith("- ") -> "•  ${line.drop(2)}"
                else -> line
            }
            val start = result.length
            result.append(text).append('\n')
            if (heading != null) {
                val scale = when (heading) { 1 -> 1.55f; 2 -> 1.3f; else -> 1.12f }
                result.setSpan(RelativeSizeSpan(scale), start, result.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                result.setSpan(StyleSpan(Typeface.BOLD), start, result.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                result.setSpan(ForegroundColorSpan(ContextCompat.getColor(context, R.color.brand_primary_dark)), start, result.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (onTimestampClick != null) {
                TimestampReferenceParser.findInLine(line, currentPart)?.let { reference ->
                    timestamp.find(text)?.let { match ->
                        result.setSpan(object : ClickableSpan() {
                            override fun onClick(widget: View) = onTimestampClick(reference)
                            override fun updateDrawState(ds: android.text.TextPaint) {
                                super.updateDrawState(ds)
                                ds.color = ContextCompat.getColor(context, R.color.brand_primary_dark)
                                ds.isUnderlineText = true
                            }
                        }, start + match.range.first, start + match.range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
            }
        }
        return result.trimEnd()
    }

    private fun stripFrontMatter(markdown: String): String {
        if (!markdown.startsWith("---")) return markdown
        val end = markdown.indexOf("\n---", startIndex = 3)
        return if (end >= 0) markdown.substring(end + 4).trimStart() else markdown
    }
}

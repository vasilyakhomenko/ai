package com.example.aichat.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.caverock.androidsvg.SVG

@Composable
fun SvgImage(svgText: String, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(16.dp)
    val bmp = remember(svgText) {
        try {
            val svg = SVG.getFromString(svgText)
            val docW = if (svg.documentWidth > 0) svg.documentWidth else 512f
            val docH = if (svg.documentHeight > 0) svg.documentHeight else 512f
            val targetW = 720
            val scale = targetW / docW
            val w = targetW
            val h = (docH * scale).toInt().coerceAtLeast(1).coerceAtMost(2000)
            svg.setDocumentWidth(w.toFloat())
            svg.setDocumentHeight(h.toFloat())
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            svg.renderToCanvas(canvas)
            // round corners on bitmap
            roundedBitmap(bitmap, 48)
        } catch (_: Exception) {
            null
        }
    }
    if (bmp != null) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = "SVG",
            modifier = modifier
                .fillMaxWidth()
                .heightIn(max = 320.dp)
                .clip(shape)
                .background(Color.Transparent, shape)
                .padding(vertical = 4.dp),
            contentScale = ContentScale.Fit
        )
    }
}

private fun roundedBitmap(src: Bitmap, radiusPx: Int): Bitmap {
    val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    val rect = android.graphics.Rect(0, 0, src.width, src.height)
    val rectF = android.graphics.RectF(rect)
    canvas.drawRoundRect(rectF, radiusPx.toFloat(), radiusPx.toFloat(), paint)
    paint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN)
    canvas.drawBitmap(src, rect, rect, paint)
    if (out != src) src.recycle()
    return out
}

fun extractSvgBlocks(content: String): List<Pair<String, Boolean>> {
    val result = mutableListOf<Pair<String, Boolean>>()
    val regex = Regex("""```svg\s*([\s\S]*?)```|<svg[\s\S]*?</svg>""", RegexOption.IGNORE_CASE)
    var last = 0
    for (m in regex.findAll(content)) {
        if (m.range.first > last) {
            result.add(content.substring(last, m.range.first) to false)
        }
        var svg = m.value
        if (svg.startsWith("```", ignoreCase = true)) {
            svg = svg.replace(Regex("""^```svg\s*""", RegexOption.IGNORE_CASE), "")
                .replace(Regex("""```$"""), "")
                .trim()
        }
        result.add(svg to true)
        last = m.range.last + 1
    }
    if (last < content.length) result.add(content.substring(last) to false)
    if (result.isEmpty()) result.add(content to false)
    return result
}

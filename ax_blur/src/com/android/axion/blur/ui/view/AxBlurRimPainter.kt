/*
 * Copyright (C) 2026 AxionOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.axion.blur.ui.view

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.Region
import com.android.axion.blur.AxBlurRim
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Shared, cached inner rim geometry for View, Compose and drawable backgrounds. */
internal class AxBlurRimPainter {
    private val outline = Path()
    private val rect = RectF()
    private val paint = Paint().apply { color = Color.WHITE }
    private val layerPaint = Paint()
    private var cachedStyle: AxBlurRim? = null
    private var cachedDensity = 0f
    private var cachedRadii: FloatArray? = null
    private var cachedRadius = 0f
    private var cachedPath: Path? = null
    private var cachedPathGeneration = -1
    private var meshes = emptyList<Mesh>()

    fun draw(
        canvas: Canvas,
        style: AxBlurRim,
        density: Float,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        cornerRadius: Float = 0f,
        cornerRadii: FloatArray? = null,
        clipPath: Path? = null,
        alpha: Int = 255,
        colorFilter: ColorFilter? = null,
    ) {
        if (!style.enabled || style.strength == 0f || Color.alpha(style.color) == 0 || alpha <= 0)
            return
        if (
            !density.isFinite() ||
                density <= 0f ||
                !left.isFinite() ||
                !top.isFinite() ||
                !right.isFinite() ||
                !bottom.isFinite() ||
                right <= left ||
                bottom <= top
        )
            return
        val radii = cornerRadii?.takeIf { it.size >= 8 }
        val radius = if (cornerRadius.isFinite()) cornerRadius.coerceAtLeast(0f) else 0f
        if (
            cachedStyle != style ||
                cachedDensity != density ||
                rect.left != left ||
                rect.top != top ||
                rect.right != right ||
                rect.bottom != bottom ||
                cachedRadius != radius ||
                !sameRadii(radii) ||
                cachedPath !== clipPath ||
                cachedPathGeneration != (clipPath?.generationId ?: -1)
        ) {
            rect.set(left, top, right, bottom)
            outline.rewind()
            when {
                clipPath != null -> outline.set(clipPath)
                radii != null -> outline.addRoundRect(rect, radii, Path.Direction.CW)
                radius > 0f -> outline.addRoundRect(rect, radius, radius, Path.Direction.CW)
                else -> outline.addRect(rect, Path.Direction.CW)
            }
            meshes = buildMeshes(style, density)
            cachedStyle = style
            cachedDensity = density
            cachedRadius = radius
            cachedRadii = radii?.copyOfRange(0, 8)
            cachedPath = clipPath
            cachedPathGeneration = clipPath?.generationId ?: -1
        }
        if (meshes.isEmpty()) return
        val save = canvas.save()
        try {
            canvas.clipRect(rect)
            canvas.clipPath(outline)
            for (mesh in meshes) {
                if (mesh.layerAlpha == 0f) continue
                val layer =
                    if (mesh.layerAlpha >= 0f) {
                        layerPaint.alpha =
                            (mesh.layerAlpha * alpha.coerceIn(0, 255) / 255f)
                                .roundToInt()
                                .coerceIn(0, 255)
                        layerPaint.colorFilter = colorFilter
                        paint.alpha = 255
                        paint.colorFilter = null
                        canvas.saveLayer(rect, layerPaint)
                    } else {
                        paint.alpha = alpha.coerceIn(0, 255)
                        paint.colorFilter = colorFilter
                        0
                    }
                canvas.drawVertices(
                    Canvas.VertexMode.TRIANGLES,
                    mesh.vertices.size,
                    mesh.vertices,
                    0,
                    null,
                    0,
                    mesh.colors,
                    0,
                    mesh.indices,
                    0,
                    mesh.indices.size,
                    paint,
                )
                if (layer != 0) canvas.restoreToCount(layer)
            }
        } finally {
            canvas.restoreToCount(save)
        }
    }

    private fun sameRadii(radii: FloatArray?): Boolean {
        val cached = cachedRadii ?: return radii == null
        return radii != null && (0 until 8).all { cached[it] == radii[it] }
    }

    private fun buildMeshes(style: AxBlurRim, density: Float): List<Mesh> {
        val result = ArrayList<Mesh>()
        val measure = PathMeasure(outline, true)
        val position = FloatArray(2)
        val tangent = FloatArray(2)
        val maxDepth = min(rect.width(), rect.height()) * 0.45f
        val filledRegion =
            Region().apply {
                setPath(
                    outline,
                    Region(
                        floor(rect.left).toInt(),
                        floor(rect.top).toInt(),
                        ceil(rect.right).toInt(),
                        ceil(rect.bottom).toInt(),
                    ),
                )
            }
        do {
            val length = measure.length
            if (!length.isFinite() || length <= 0f) continue
            val count = ceil(length / 2f).toInt().coerceIn(16, 2048)
            val points = FloatArray(count * 2)
            val normals = FloatArray(count * 2)
            for (i in 0 until count) {
                measure.getPosTan(length * i / count, position, tangent)
                points[i * 2] = position[0]
                points[i * 2 + 1] = position[1]
                normals[i * 2] = -tangent[1]
                normals[i * 2 + 1] = tangent[0]
            }
            var inwardVotes = 0
            for (i in 0 until count) {
                val x = points[i * 2]
                val y = points[i * 2 + 1]
                val nx = normals[i * 2]
                val ny = normals[i * 2 + 1]
                val forward =
                    filledRegion.contains(floor(x + nx * 2f).toInt(), floor(y + ny * 2f).toInt())
                val backward =
                    filledRegion.contains(floor(x - nx * 2f).toInt(), floor(y - ny * 2f).toInt())
                if (forward != backward) inwardVotes += if (forward) 1 else -1
            }
            var signedArea = 0f
            for (i in 0 until count) {
                val next = (i + 1) % count
                signedArea +=
                    points[i * 2] * points[next * 2 + 1] - points[next * 2] * points[i * 2 + 1]
            }
            if (inwardVotes < 0 || (inwardVotes == 0 && signedArea < 0f)) {
                for (i in normals.indices) normals[i] = -normals[i]
            }
            if (style.glowDepthDp > 0f && style.glowAlpha > 0f) {
                result +=
                    mesh(
                        points,
                        normals,
                        style,
                        min(style.glowDepthDp * density, maxDepth),
                        GLOW_DEPTHS,
                        GLOW_ALPHAS,
                        style.glowAlpha,
                    )
            }
            if (style.edgeWidthDp > 0f) {
                result +=
                    mesh(
                        points,
                        normals,
                        style,
                        min(style.edgeWidthDp * density, maxDepth),
                        EDGE_DEPTHS,
                        EDGE_ALPHAS,
                        1f,
                    )
            }
        } while (measure.nextContour())
        return result
    }

    private fun mesh(
        points: FloatArray,
        normals: FloatArray,
        style: AxBlurRim,
        depth: Float,
        depths: FloatArray,
        alphas: FloatArray,
        opacity: Float,
    ): Mesh {
        val count = points.size / 2
        val vertices = FloatArray(count * depths.size * 2)
        val colors = IntArray(count * depths.size)
        val indices = ShortArray(count * (depths.size - 1) * 6)
        val rgb = style.color and 0x00ffffff
        val depthLimits = FloatArray(count) { depth }
        var needsLayer = false
        for (i in 0 until count) {
            val previous = (i + count - 1) % count
            val next = (i + 1) % count
            val nx = normals[next * 2] - normals[previous * 2]
            val ny = normals[next * 2 + 1] - normals[previous * 2 + 1]
            val change = sqrt(nx * nx + ny * ny)
            if (change > 0.0001f) {
                val dx = points[next * 2] - points[previous * 2]
                val dy = points[next * 2 + 1] - points[previous * 2 + 1]
                val limit = sqrt(dx * dx + dy * dy) / change * 0.95f
                if (depth > limit) {
                    depthLimits[i] = limit
                    needsLayer = true
                }
            }
        }
        val maxAlpha =
            Color.alpha(style.color) *
                style.strength *
                maxOf(style.topAlpha, style.bottomAlpha, style.sideAlpha) *
                opacity
        val normalization = if (needsLayer && maxAlpha > 0f) 255f / maxAlpha else 1f
        for (i in 0 until count) {
            val nx = normals[i * 2]
            val ny = normals[i * 2 + 1]
            val previous = (i + count - 1) % count
            val next = (i + 1) % count
            val normalDx = normals[next * 2] - normals[previous * 2]
            val normalDy = normals[next * 2 + 1] - normals[previous * 2 + 1]
            val normalChange = sqrt(normalDx * normalDx + normalDy * normalDy)
            val curvature = (normalChange / 0.002f).coerceIn(0f, 1f)
            val corner =
                (2f * abs(nx * ny)).coerceIn(0f, 1f).pow(style.cornerTaperPower) * curvature
            val widthScale = 1f - corner * (1f - style.cornerWidthScale)
            val alphaScale = 1f - corner * (1f - style.cornerAlphaScale)
            val edgeAlpha =
                style.sideAlpha +
                    abs(ny) *
                        ((if (ny >= 0f) style.topAlpha else style.bottomAlpha) - style.sideAlpha)
            val peakAlpha =
                Color.alpha(style.color) *
                    style.strength *
                    edgeAlpha *
                    alphaScale *
                    opacity *
                    normalization
            for (level in depths.indices) {
                val vertex = level * count + i
                val inset = depthLimits[i] * widthScale * depths[level]
                vertices[vertex * 2] = points[i * 2] + nx * inset
                vertices[vertex * 2 + 1] = points[i * 2 + 1] + ny * inset
                colors[vertex] =
                    rgb or ((peakAlpha * alphas[level]).roundToInt().coerceIn(0, 255) shl 24)
            }
        }
        var index = 0
        for (level in 0 until depths.lastIndex) {
            for (i in 0 until count) {
                val next = (i + 1) % count
                val outer = level * count + i
                val outerNext = level * count + next
                val inner = outer + count
                val innerNext = outerNext + count
                indices[index++] = outer.toShort()
                indices[index++] = outerNext.toShort()
                indices[index++] = inner.toShort()
                indices[index++] = inner.toShort()
                indices[index++] = outerNext.toShort()
                indices[index++] = innerNext.toShort()
            }
        }
        return Mesh(vertices, colors, indices, if (needsLayer) maxAlpha else -1f)
    }

    private class Mesh(
        val vertices: FloatArray,
        val colors: IntArray,
        val indices: ShortArray,
        val layerAlpha: Float,
    )

    companion object {
        private val EDGE_DEPTHS = floatArrayOf(0f, 0.2f, 0.5f, 0.8f, 1f)
        private val EDGE_ALPHAS = floatArrayOf(0f, 0.7f, 1f, 0.5f, 0f)
        private val GLOW_DEPTHS = floatArrayOf(0f, 0.08f, 0.2f, 0.4f, 0.65f, 1f)
        private val GLOW_ALPHAS = floatArrayOf(0f, 1f, 0.72f, 0.38f, 0.12f, 0f)
    }
}

package com.akaa.autoclicker.utils

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.util.Base64
import android.util.Log
import android.util.LruCache
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

object ImageMatcher {

    private const val TAG = "ImageMatcher"
    
    // In-memory cache for decoded template bitmaps to prevent main-thread lag
    private val bitmapCache = LruCache<String, Bitmap>(30)

    /**
     * Converts a Bitmap to a compact Base64 JPEG/PNG string (max 120x120 px)
     */
    fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        val maxDim = 120
        val targetBitmap = if (bitmap.width > maxDim || bitmap.height > maxDim) {
            val scale = maxDim.toFloat() / max(bitmap.width, bitmap.height)
            val w = (bitmap.width * scale).toInt().coerceAtLeast(10)
            val h = (bitmap.height * scale).toInt().coerceAtLeast(10)
            Bitmap.createScaledBitmap(bitmap, w, h, true)
        } else {
            bitmap
        }
        
        targetBitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
        val byteArray = outputStream.toByteArray()
        val base64 = Base64.encodeToString(byteArray, Base64.NO_WRAP)
        
        // Cache this bitmap
        bitmapCache.put(base64, targetBitmap)
        return base64
    }

    /**
     * Converts Base64 string back to Bitmap with LRU caching
     */
    fun base64ToBitmap(base64: String?): Bitmap? {
        if (base64.isNullOrBlank()) return null
        
        // Check memory cache first
        val cached = bitmapCache.get(base64)
        if (cached != null && !cached.isRecycled) {
            return cached
        }

        return try {
            val decodedBytes = Base64.decode(base64, Base64.DEFAULT)
            val bm = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
            if (bm != null) {
                bitmapCache.put(base64, bm)
            }
            bm
        } catch (e: Exception) {
            Log.e(TAG, "Error decoding base64 image", e)
            null
        }
    }

    /**
     * Matches target template against the screen bitmap inside searchRegion
     * Uses Normalized Cross-Correlation (NCC) + Mean Absolute Color Difference to prevent false positives.
     */
    fun matchTemplate(
        screenBitmap: Bitmap,
        templateBitmap: Bitmap,
        searchRegion: Rect? = null,
        threshold: Float = 0.80f
    ): Boolean {
        try {
            // Determine region to search
            val region = if (searchRegion != null && searchRegion.width() > 10 && searchRegion.height() > 10) {
                val left = searchRegion.left.coerceIn(0, screenBitmap.width - 1)
                val top = searchRegion.top.coerceIn(0, screenBitmap.height - 1)
                val right = searchRegion.right.coerceIn(left + 1, screenBitmap.width)
                val bottom = searchRegion.bottom.coerceIn(top + 1, screenBitmap.height)
                Rect(left, top, right, bottom)
            } else {
                Rect(0, 0, screenBitmap.width, screenBitmap.height)
            }

            val regionWidth = region.width()
            val regionHeight = region.height()

            if (regionWidth < 10 || regionHeight < 10) return false

            val croppedScreen = Bitmap.createBitmap(
                screenBitmap,
                region.left,
                region.top,
                regionWidth,
                regionHeight
            )

            // Scale both images to standard comparison resolution (48x48)
            val compSize = 48
            val scaledScreen = Bitmap.createScaledBitmap(croppedScreen, compSize, compSize, true)
            val scaledTemplate = Bitmap.createScaledBitmap(templateBitmap, compSize, compSize, true)

            val totalPixels = compSize * compSize
            val gray1 = DoubleArray(totalPixels)
            val gray2 = DoubleArray(totalPixels)

            var mean1 = 0.0
            var mean2 = 0.0
            var totalColorDiff = 0.0

            var idx = 0
            for (y in 0 until compSize) {
                for (x in 0 until compSize) {
                    val p1 = scaledScreen.getPixel(x, y)
                    val p2 = scaledTemplate.getPixel(x, y)

                    val r1 = Color.red(p1)
                    val g1 = Color.green(p1)
                    val b1 = Color.blue(p1)

                    val r2 = Color.red(p2)
                    val g2 = Color.green(p2)
                    val b2 = Color.blue(p2)

                    val gVal1 = 0.299 * r1 + 0.587 * g1 + 0.114 * b1
                    val gVal2 = 0.299 * r2 + 0.587 * g2 + 0.114 * b2

                    gray1[idx] = gVal1
                    gray2[idx] = gVal2

                    mean1 += gVal1
                    mean2 += gVal2

                    val rDiff = abs(r1 - r2)
                    val gDiff = abs(g1 - g2)
                    val bDiff = abs(b1 - b2)
                    totalColorDiff += (rDiff + gDiff + bDiff) / 3.0

                    idx++
                }
            }

            mean1 /= totalPixels
            mean2 /= totalPixels
            val avgColorDiff = totalColorDiff / totalPixels

            // Compute Standard Deviations & Cross Correlation
            var var1 = 0.0
            var var2 = 0.0
            var crossCorr = 0.0

            for (i in 0 until totalPixels) {
                val d1 = gray1[i] - mean1
                val d2 = gray2[i] - mean2
                var1 += d1 * d1
                var2 += d2 * d2
                crossCorr += d1 * d2
            }

            val std1 = sqrt(var1 / totalPixels)
            val std2 = sqrt(var2 / totalPixels)

            val nccScore: Double
            if (std1 < 4.0 && std2 < 4.0) {
                // Both images are solid / uniform flat colors
                val meanDiff = abs(mean1 - mean2)
                nccScore = if (meanDiff < 15.0) 1.0 else (1.0 - (meanDiff / 255.0))
            } else if (std1 < 4.0 || std2 < 4.0) {
                // One is patterned, one is blank -> definite mismatch
                nccScore = 0.0
            } else {
                nccScore = (crossCorr / (totalPixels * std1 * std2)).coerceIn(-1.0, 1.0)
            }

            // Combined Similarity Score (Correlation + Color Consistency)
            val colorSimilarity = (1.0 - (avgColorDiff / 255.0)).coerceIn(0.0, 1.0)
            val finalScore = if (nccScore > 0) {
                (nccScore * 0.7 + colorSimilarity * 0.3).toFloat()
            } else {
                0f
            }

            val isMatched = finalScore >= threshold && avgColorDiff <= 65.0

            Log.d(
                TAG,
                "Image Match Check: NCC=${String.format("%.2f", nccScore)}, ColorSim=${String.format("%.2f", colorSimilarity)}, FinalScore=${String.format("%.2f", finalScore)}, Threshold=$threshold -> MATCH=$isMatched"
            )

            return isMatched
        } catch (e: Exception) {
            Log.e(TAG, "Error matching template", e)
            return false
        }
    }

    /**
     * Computes similarity score and returns Pair of isMatched to score (0.0 to 1.0)
     */
    fun computeSimilarityScore(
        screenBitmap: Bitmap,
        templateBitmap: Bitmap,
        searchRegion: Rect? = null,
        threshold: Float = 0.70f
    ): Pair<Boolean, Float> {
        try {
            val region = if (searchRegion != null && searchRegion.width() > 10 && searchRegion.height() > 10) {
                val left = searchRegion.left.coerceIn(0, screenBitmap.width - 1)
                val top = searchRegion.top.coerceIn(0, screenBitmap.height - 1)
                val right = searchRegion.right.coerceIn(left + 1, screenBitmap.width)
                val bottom = searchRegion.bottom.coerceIn(top + 1, screenBitmap.height)
                Rect(left, top, right, bottom)
            } else {
                Rect(0, 0, screenBitmap.width, screenBitmap.height)
            }

            val regionWidth = region.width()
            val regionHeight = region.height()
            if (regionWidth < 10 || regionHeight < 10) return Pair(false, 0f)

            val croppedScreen = Bitmap.createBitmap(screenBitmap, region.left, region.top, regionWidth, regionHeight)
            val compSize = 48
            val scaledScreen = Bitmap.createScaledBitmap(croppedScreen, compSize, compSize, true)
            val scaledTemplate = Bitmap.createScaledBitmap(templateBitmap, compSize, compSize, true)

            val totalPixels = compSize * compSize
            val gray1 = DoubleArray(totalPixels)
            val gray2 = DoubleArray(totalPixels)
            var mean1 = 0.0
            var mean2 = 0.0
            var totalColorDiff = 0.0

            var idx = 0
            for (y in 0 until compSize) {
                for (x in 0 until compSize) {
                    val p1 = scaledScreen.getPixel(x, y)
                    val p2 = scaledTemplate.getPixel(x, y)
                    val r1 = Color.red(p1); val g1 = Color.green(p1); val b1 = Color.blue(p1)
                    val r2 = Color.red(p2); val g2 = Color.green(p2); val b2 = Color.blue(p2)

                    val gVal1 = 0.299 * r1 + 0.587 * g1 + 0.114 * b1
                    val gVal2 = 0.299 * r2 + 0.587 * g2 + 0.114 * b2
                    gray1[idx] = gVal1; gray2[idx] = gVal2
                    mean1 += gVal1; mean2 += gVal2
                    val rDiff = abs(r1 - r2); val gDiff = abs(g1 - g2); val bDiff = abs(b1 - b2)
                    totalColorDiff += (rDiff + gDiff + bDiff) / 3.0
                    idx++
                }
            }

            mean1 /= totalPixels
            mean2 /= totalPixels
            val avgColorDiff = totalColorDiff / totalPixels

            var var1 = 0.0; var var2 = 0.0; var crossCorr = 0.0
            for (i in 0 until totalPixels) {
                val d1 = gray1[i] - mean1
                val d2 = gray2[i] - mean2
                var1 += d1 * d1
                var2 += d2 * d2
                crossCorr += d1 * d2
            }

            val std1 = sqrt(var1 / totalPixels)
            val std2 = sqrt(var2 / totalPixels)

            val nccScore: Double
            if (std1 < 4.0 && std2 < 4.0) {
                val meanDiff = abs(mean1 - mean2)
                nccScore = if (meanDiff < 15.0) 1.0 else (1.0 - (meanDiff / 255.0))
            } else if (std1 < 4.0 || std2 < 4.0) {
                nccScore = 0.0
            } else {
                nccScore = (crossCorr / (totalPixels * std1 * std2)).coerceIn(-1.0, 1.0)
            }

            val colorSimilarity = (1.0 - (avgColorDiff / 255.0)).coerceIn(0.0, 1.0)
            val finalScore = if (nccScore > 0) {
                (nccScore * 0.7 + colorSimilarity * 0.3).toFloat().coerceIn(0f, 1f)
            } else {
                0f
            }

            val isMatched = finalScore >= threshold && avgColorDiff <= 65.0
            return Pair(isMatched, finalScore)
        } catch (e: Exception) {
            Log.e(TAG, "Error in computeSimilarityScore", e)
            return Pair(false, 0f)
        }
    }
}

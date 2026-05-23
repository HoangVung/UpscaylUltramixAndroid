package com.vung.upscaylultramix

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect

object TileProcessor {
    interface ProgressListener {
        fun onProgress(done: Int, total: Int)
        fun isCancelled(): Boolean
    }

    fun processImage(
        inputBitmap: Bitmap,
        runner: TfliteModelRunner,
        listener: ProgressListener
    ): Bitmap? {
        val W = inputBitmap.width
        val H = inputBitmap.height
        val tileW = runner.inputWidth
        val tileH = runner.inputHeight
        val scale = runner.outputScale

        val cols = (W + tileW - 1) / tileW
        val rows = (H + tileH - 1) / tileH
        val totalTiles = rows * cols

        val outputW = W * scale
        val outputH = H * scale

        val outputBitmap = Bitmap.createBitmap(outputW, outputH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(outputBitmap)
        val paint = Paint()

        var doneTiles = 0

        // Create temporary tile for input reuse
        val tempInputTile = Bitmap.createBitmap(tileW, tileH, Bitmap.Config.ARGB_8888)
        val inputCanvas = Canvas(tempInputTile)

        val srcRect = Rect()
        val dstRect = Rect()
        val outSrcRect = Rect()
        val outDstRect = Rect()

        for (r in 0 until rows) {
            for (c in 0 until cols) {
                if (listener.isCancelled()) {
                    outputBitmap.recycle()
                    tempInputTile.recycle()
                    return null
                }

                val x = c * tileW
                val y = r * tileH
                val wActual = minOf(tileW, W - x)
                val hActual = minOf(tileH, H - y)

                // Fill black first to clear previous tile data in padding zones
                tempInputTile.eraseColor(android.graphics.Color.BLACK)

                // Copy actual part of source image to temp input tile
                srcRect.set(x, y, x + wActual, y + hActual)
                dstRect.set(0, 0, wActual, hActual)
                inputCanvas.drawBitmap(inputBitmap, srcRect, dstRect, paint)

                // Run inference on the 128x128 tile
                val tempOutputTile = runner.runInference(tempInputTile)

                // Draw the actual part of output tile onto final canvas
                val wOutActual = wActual * scale
                val hOutActual = hActual * scale
                outSrcRect.set(0, 0, wOutActual, hOutActual)
                outDstRect.set(x * scale, y * scale, x * scale + wOutActual, y * scale + hOutActual)
                canvas.drawBitmap(tempOutputTile, outSrcRect, outDstRect, paint)

                tempOutputTile.recycle()

                doneTiles++
                listener.onProgress(doneTiles, totalTiles)
            }
        }

        tempInputTile.recycle()
        return outputBitmap
    }
}

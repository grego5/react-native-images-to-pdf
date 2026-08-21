package com.margelo.nitro.imagespdf

import android.graphics.Matrix
import android.graphics.RectF
import androidx.exifinterface.media.ExifInterface

internal data class PdfMatrix(
  val a: Float,
  val b: Float,
  val c: Float,
  val d: Float,
  val e: Float,
  val f: Float
)

internal data class ExifPdfTransform(
  val rotationDegrees: Int,
  val isFlipped: Boolean
) {
  val swapsDimensions: Boolean
    get() = rotationDegrees == 90 || rotationDegrees == 270

  fun bitmapMatrix(rawWidth: Int, rawHeight: Int): Matrix {
    val matrix = Matrix()
    if (isFlipped) {
      matrix.postScale(-1f, 1f)
    }
    if (rotationDegrees != 0) {
      matrix.postRotate(rotationDegrees.toFloat())
    }

    val bounds = RectF(0f, 0f, rawWidth.toFloat(), rawHeight.toFloat())
    matrix.mapRect(bounds)
    matrix.postTranslate(-bounds.left, -bounds.top)
    return matrix
  }

  fun pdfMatrix(
    pixelWidth: Int,
    pixelHeight: Int,
    destination: RectF
  ): PdfMatrix {
    val orientation = bitmapMatrix(pixelWidth, pixelHeight)
    val values = FloatArray(9)
    orientation.getValues(values)
    val orientedWidth = if (swapsDimensions) pixelHeight else pixelWidth
    val orientedHeight = if (swapsDimensions) pixelWidth else pixelHeight
    val scaleX = destination.width() / orientedWidth.toFloat()
    val scaleY = destination.height() / orientedHeight.toFloat()
    val a = values[Matrix.MSCALE_X]
    val b = values[Matrix.MSKEW_Y]
    val c = values[Matrix.MSKEW_X]
    val d = values[Matrix.MSCALE_Y]
    val e = values[Matrix.MTRANS_X]
    val f = values[Matrix.MTRANS_Y]

    // PDF image space has a top-left origin; PDF page space has a bottom-left origin.
    return PdfMatrix(
      // PDF image XObjects are drawn in a normalized unit square. Convert the
      // pixel-space orientation matrix into that coordinate system before
      // applying the destination rectangle.
      a = scaleX * a * pixelWidth,
      b = -scaleY * b * pixelWidth,
      c = -scaleX * c * pixelHeight,
      d = scaleY * d * pixelHeight,
      e = scaleX * (e + c * pixelHeight) + destination.left,
      f = destination.top + destination.height() - scaleY * (f + d * pixelHeight)
    )
  }

  companion object {
    val IDENTITY = ExifPdfTransform(rotationDegrees = 0, isFlipped = false)

    fun from(exif: ExifInterface): ExifPdfTransform {
      return ExifPdfTransform(
        rotationDegrees = exif.rotationDegrees,
        isFlipped = exif.isFlipped
      )
    }
  }
}

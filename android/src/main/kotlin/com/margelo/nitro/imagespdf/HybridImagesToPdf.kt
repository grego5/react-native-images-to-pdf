package com.margelo.nitro.imagespdf

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.system.Os
import androidx.annotation.Keep
import androidx.exifinterface.media.ExifInterface
import com.facebook.proguard.annotations.DoNotStrip
import com.margelo.nitro.core.Promise
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@Keep
@DoNotStrip
class HybridImagesToPdf : HybridImagesToPdfSpec() {
  companion object {
    private const val DEFAULT_JPEG_QUALITY = 0.72
    private const val DEFAULT_TARGET_DPI = 200.0
  }

  private data class PageSize(val width: Int, val height: Int)

  private data class CropSize(val width: Double, val height: Double)

  private data class ImageInfo(
    val rawWidth: Int,
    val rawHeight: Int,
    val exifTransform: ExifPdfTransform,
    val width: Int,
    val height: Int,
    val mimeType: String?
  )

  private data class PdfPageImage(
    val jpegData: ByteArrayOutputStream,
    val pixelWidth: Int,
    val pixelHeight: Int,
    val exifTransform: ExifPdfTransform,
    val pageSize: PageSize,
    val destination: RectF
  )

  private class PdfOutput(file: File) : Closeable {
    private val stream = BufferedOutputStream(FileOutputStream(file))
    var position = 0L
      private set

    fun write(bytes: ByteArray) {
      stream.write(bytes)
      position += bytes.size
    }

    fun write(source: ByteArrayOutputStream) {
      source.writeTo(stream)
      position += source.size()
    }

    fun writeAscii(value: String) {
      write(value.toByteArray(StandardCharsets.US_ASCII))
    }

    override fun close() {
      stream.close()
    }
  }

  override fun createPdf(options: CreatePdfOptions): Promise<String> {
    return Promise.parallel { createPdfBlocking(options) }
  }

  private fun createPdfBlocking(options: CreatePdfOptions): String {
    if (options.pages.isEmpty()) {
      throw IllegalArgumentException("No pages provided.")
    }

    val outputFile = localFile(options.outputPath, "Output path")
    validateOutputFile(outputFile)
    val requestedPageSize = pageSize(
      options.pageWidth,
      options.pageHeight,
      options.pageAspectRatio
    )
    val targetDpi = targetDpi(options.targetDpi, requestedPageSize)
    val jpegQuality = jpegQuality(options.jpegQuality)
    val temporaryFile = File(
      outputFile.parentFile
        ?: throw IllegalArgumentException("Output path '${outputFile.path}' has no parent directory."),
      "${outputFile.name}.tmp"
    )
    check(temporaryFile.createNewFile()) {
      "Could not create temporary PDF at '${temporaryFile.path}'."
    }

    try {
      writePdf(
        pages = options.pages,
        requestedPageSize = requestedPageSize,
        imageFit = options.imageFit,
        autoRotateExif = options.autoRotateExif ?: true,
        targetDpi = targetDpi,
        jpegQuality = jpegQuality,
        outputFile = temporaryFile
      )
      Os.rename(temporaryFile.path, outputFile.path)
    } finally {
      if (temporaryFile.exists()) {
        temporaryFile.delete()
      }
    }
    return Uri.fromFile(outputFile).toString()
  }

  private fun processImage(
    imagePath: String,
    requestedPageSize: PageSize?,
    imageFit: ImageFit?,
    autoRotateExif: Boolean,
    targetDpi: Double?,
    jpegQuality: Int?
  ): PdfPageImage {
    val imageFile = localFile(imagePath, "Image path")
    val imageInfo = imageInfo(imageFile, autoRotateExif)
    val resolvedPageSize = requestedPageSize ?: PageSize(imageInfo.width, imageInfo.height)
    val fit = if (requestedPageSize == null) ImageFit.NONE else imageFit ?: ImageFit.NONE
    val destination = destinationRect(
      imageInfo.width,
      imageInfo.height,
      resolvedPageSize.width,
      resolvedPageSize.height,
      fit
    )
    val cropSize = if (fit == ImageFit.COVER) {
      val scale = max(
        resolvedPageSize.width / imageInfo.width.toDouble(),
        resolvedPageSize.height / imageInfo.height.toDouble()
      )
      CropSize(
        width = resolvedPageSize.width / scale,
        height = resolvedPageSize.height / scale
      )
    } else {
      null
    }
    val encodedSourceWidth = cropSize?.width ?: imageInfo.width.toDouble()
    val encodedSourceHeight = cropSize?.height ?: imageInfo.height.toDouble()
    val renderedDestination = if (fit == ImageFit.COVER) {
      RectF(
        0f,
        0f,
        resolvedPageSize.width.toFloat(),
        resolvedPageSize.height.toFloat()
      )
    } else {
      destination
    }
    val targetSize = targetPixelSize(
      encodedSourceWidth,
      encodedSourceHeight,
      resolvedPageSize,
      renderedDestination,
      fit,
      targetDpi
    )

    if (
      jpegQuality == null &&
      imageInfo.mimeType?.equals("image/jpeg", ignoreCase = true) == true &&
      cropSize == null &&
      targetSize.first == imageInfo.width &&
      targetSize.second == imageInfo.height &&
      isRgbJpeg(imageFile)
    ) {
      val jpegOutput = ByteArrayOutputStream()
      FileInputStream(imageFile).use { inputStream ->
        inputStream.copyTo(jpegOutput)
      }
      return PdfPageImage(
        jpegData = jpegOutput,
        pixelWidth = imageInfo.rawWidth,
        pixelHeight = imageInfo.rawHeight,
        exifTransform = imageInfo.exifTransform,
        pageSize = resolvedPageSize,
        destination = renderedDestination
      )
    }

    val bitmap = renderBitmap(
      imageFile,
      imageInfo,
      targetSize.first,
      targetSize.second,
      cropSize
    )

    try {
      val jpegOutput = ByteArrayOutputStream()
      check(bitmap.compress(
        Bitmap.CompressFormat.JPEG,
        jpegQuality ?: (DEFAULT_JPEG_QUALITY * 100).roundToInt(),
        jpegOutput
      )) {
        "Image path '$imagePath' could not be JPEG encoded."
      }
      return PdfPageImage(
        jpegData = jpegOutput,
        pixelWidth = bitmap.width,
        pixelHeight = bitmap.height,
        exifTransform = ExifPdfTransform.IDENTITY,
        pageSize = resolvedPageSize,
        destination = renderedDestination
      )
    } catch (error: Throwable) {
      throw IllegalArgumentException(
        "Image path '$imagePath' could not be processed.",
        error
      )
    } finally {
      bitmap.recycle()
    }
  }

  private fun targetPixelSize(
    sourceWidth: Double,
    sourceHeight: Double,
    pageSize: PageSize,
    destination: RectF,
    imageFit: ImageFit,
    targetDpi: Double?
  ): Pair<Int, Int> {
    if (targetDpi == null) {
      return sourceWidth.roundToInt() to sourceHeight.roundToInt()
    }

    val pixelsPerPoint = targetDpi / 72.0
    if (imageFit == ImageFit.FILL) {
      return max(1, min(sourceWidth.roundToInt(), (pageSize.width * pixelsPerPoint).roundToInt())) to
        max(1, min(sourceHeight.roundToInt(), (pageSize.height * pixelsPerPoint).roundToInt()))
    }

    val widthScale = abs(destination.width().toDouble()) * pixelsPerPoint / sourceWidth
    val heightScale = abs(destination.height().toDouble()) * pixelsPerPoint / sourceHeight
    val scale = min(1.0, max(widthScale, heightScale))
    return max(1, (sourceWidth * scale).roundToInt()) to
      max(1, (sourceHeight * scale).roundToInt())
  }

  private fun imageInfo(file: File, autoRotateExif: Boolean): ImageInfo {
    if (!file.isFile || !file.canRead()) {
      throw IllegalArgumentException("Image path '${file.path}' is not readable.")
    }

    val options = BitmapFactory.Options().apply {
      inJustDecodeBounds = true
    }
    FileInputStream(file).use { inputStream ->
      BitmapFactory.decodeStream(inputStream, null, options)
    }
    if (options.outWidth <= 0 || options.outHeight <= 0) {
      throw IllegalArgumentException(
        "Image path '${file.path}' cannot be decoded into a bitmap."
      )
    }

    val exifTransform = if (autoRotateExif) {
      try {
        ExifPdfTransform.from(ExifInterface(file))
      } catch (_: Exception) {
        ExifPdfTransform.IDENTITY
      }
    } else {
      ExifPdfTransform.IDENTITY
    }

    return ImageInfo(
      rawWidth = options.outWidth,
      rawHeight = options.outHeight,
      exifTransform = exifTransform,
      width = if (exifTransform.swapsDimensions) options.outHeight else options.outWidth,
      height = if (exifTransform.swapsDimensions) options.outWidth else options.outHeight,
      mimeType = options.outMimeType
    )
  }

  private fun isRgbJpeg(file: File): Boolean {
    return try {
      FileInputStream(file).use { inputStream ->
        if (inputStream.read() != 0xFF || inputStream.read() != 0xD8) {
          return@use false
        }

        while (true) {
          var marker = inputStream.read()
          while (marker == 0xFF) {
            marker = inputStream.read()
          }
          if (marker < 0 || marker == 0xD9 || marker == 0xDA) {
            return@use false
          }

          if (marker in 0xD0..0xD7 || marker == 0x01) {
            continue
          }

          val lengthHigh = inputStream.read()
          val lengthLow = inputStream.read()
          if (lengthHigh < 0 || lengthLow < 0) {
            return@use false
          }
          val length = (lengthHigh shl 8) or lengthLow
          if (length < 2) {
            return@use false
          }

          val isStartOfFrame = marker in 0xC0..0xC3 ||
            marker in 0xC5..0xC7 ||
            marker in 0xC9..0xCB ||
            marker in 0xCD..0xCF
          if (isStartOfFrame) {
            if (length < 8) {
              return@use false
            }
            repeat(5) {
              if (inputStream.read() < 0) {
                return@use false
              }
            }
            return@use inputStream.read() == 3
          }

          var remaining = length - 2
          while (remaining > 0) {
            val skipped = inputStream.skip(remaining.toLong()).toInt()
            if (skipped <= 0) {
              return@use false
            }
            remaining -= skipped
          }
        }

        false
      }
    } catch (_: Exception) {
      false
    }
  }

  private fun renderBitmap(
    file: File,
    info: ImageInfo,
    targetWidth: Int,
    targetHeight: Int,
    cropSize: CropSize?
  ): Bitmap {
    val swapsDimensions = info.exifTransform.swapsDimensions
    val rawTargetWidth = if (swapsDimensions) targetHeight else targetWidth
    val rawTargetHeight = if (swapsDimensions) targetWidth else targetHeight
    var sampleSize = 1
    while (
      info.rawWidth / (sampleSize * 2) >= rawTargetWidth &&
      info.rawHeight / (sampleSize * 2) >= rawTargetHeight
    ) {
      sampleSize *= 2
    }

    val options = BitmapFactory.Options().apply {
      inSampleSize = sampleSize
      inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    val decoded = FileInputStream(file).use { inputStream ->
      BitmapFactory.decodeStream(inputStream, null, options)
        ?: throw IllegalArgumentException(
          "Image path '${file.path}' cannot be decoded into a bitmap."
        )
    }
    return try {
      val output = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
      try {
        val swapsDimensions = info.exifTransform.swapsDimensions
        val logicalWidth = if (swapsDimensions) decoded.height else decoded.width
        val logicalHeight = if (swapsDimensions) decoded.width else decoded.height
        val cropWidth = cropSize?.let {
          max(1, min(logicalWidth, (logicalWidth * it.width / info.width).roundToInt()))
        } ?: logicalWidth
        val cropHeight = cropSize?.let {
          max(1, min(logicalHeight, (logicalHeight * it.height / info.height).roundToInt()))
        } ?: logicalHeight
        val cropLeft = (logicalWidth - cropWidth) / 2f
        val cropTop = (logicalHeight - cropHeight) / 2f

        Canvas(output).apply {
          drawColor(Color.WHITE)
          save()
          scale(
            targetWidth / cropWidth.toFloat(),
            targetHeight / cropHeight.toFloat()
          )
          translate(-cropLeft, -cropTop)
          concat(info.exifTransform.bitmapMatrix(decoded.width, decoded.height))
          drawBitmap(decoded, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
          restore()
        }
        output.setHasAlpha(false)
        output
      } catch (error: Throwable) {
        output.recycle()
        throw error
      }
    } finally {
      decoded.recycle()
    }
  }

  private fun writePdf(
    pages: Array<String>,
    requestedPageSize: PageSize?,
    imageFit: ImageFit?,
    autoRotateExif: Boolean,
    targetDpi: Double?,
    jpegQuality: Int?,
    outputFile: File
  ) {
    val objectCount = 2 + pages.size * 3
    val offsets = LongArray(objectCount + 1)

    PdfOutput(outputFile).use { pdf ->
      pdf.writeAscii("%PDF-1.4\n")
      pdf.write(byteArrayOf(0x25, 0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), 0x0A))

      appendObject(pdf, offsets, 1) {
        it.writeAscii("<< /Type /Catalog /Pages 2 0 R >>")
      }
      val pageReferences = pages.indices.joinToString(" ") { "${3 + it * 3} 0 R" }
      appendObject(pdf, offsets, 2) {
        it.writeAscii("<< /Type /Pages /Count ${pages.size} /Kids [$pageReferences] >>")
      }

      pages.forEachIndexed { index, imagePath ->
        val page = processImage(
          imagePath,
          requestedPageSize,
          imageFit,
          autoRotateExif,
          targetDpi,
          jpegQuality
        )
        writePage(pdf, offsets, index, page)
      }

      val xrefOffset = pdf.position
      pdf.writeAscii("xref\n0 ${objectCount + 1}\n")
      pdf.writeAscii("0000000000 65535 f \n")
      for (objectNumber in 1..objectCount) {
        pdf.writeAscii("${offsets[objectNumber].toString().padStart(10, '0')} 00000 n \n")
      }
      pdf.writeAscii(
        "trailer\n<< /Size ${objectCount + 1} /Root 1 0 R >>\n" +
          "startxref\n$xrefOffset\n%%EOF\n"
      )
    }
  }

  private fun writePage(
    pdf: PdfOutput,
    offsets: LongArray,
    index: Int,
    page: PdfPageImage
  ) {
    val pageObject = 3 + index * 3
    val contentObject = pageObject + 1
    val imageObject = pageObject + 2
    val pageWidth = pdfNumber(page.pageSize.width.toFloat())
    val pageHeight = pdfNumber(page.pageSize.height.toFloat())
    val imageMatrix = page.exifTransform.pdfMatrix(
      page.pixelWidth,
      page.pixelHeight,
      page.destination
    )

    appendObject(pdf, offsets, pageObject) {
      it.writeAscii(
        "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 $pageWidth $pageHeight] " +
          "/Resources << /XObject << /Im0 $imageObject 0 R >> >> " +
          "/Contents $contentObject 0 R >>"
      )
    }

    val commands = "q\n0 0 $pageWidth $pageHeight re W n\n" +
      "${pdfNumber(imageMatrix.a)} ${pdfNumber(imageMatrix.b)} " +
      "${pdfNumber(imageMatrix.c)} ${pdfNumber(imageMatrix.d)} " +
      "${pdfNumber(imageMatrix.e)} ${pdfNumber(imageMatrix.f)} cm\n" +
      "/Im0 Do\nQ\n"
    val commandBytes = commands.toByteArray(StandardCharsets.US_ASCII)
    appendObject(pdf, offsets, contentObject) {
      it.writeAscii("<< /Length ${commandBytes.size} >>\nstream\n")
      it.write(commandBytes)
      it.writeAscii("endstream")
    }

    appendObject(pdf, offsets, imageObject) {
      it.writeAscii(
        "<< /Type /XObject /Subtype /Image /Width ${page.pixelWidth} " +
          "/Height ${page.pixelHeight} /ColorSpace /DeviceRGB " +
          "/BitsPerComponent 8 /Interpolate true /Filter /DCTDecode " +
          "/Length ${page.jpegData.size()} >>\nstream\n"
      )
      it.write(page.jpegData)
      it.writeAscii("\nendstream")
    }
  }

  private fun appendObject(
    pdf: PdfOutput,
    offsets: LongArray,
    number: Int,
    body: (PdfOutput) -> Unit
  ) {
    offsets[number] = pdf.position
    pdf.writeAscii("$number 0 obj\n")
    body(pdf)
    pdf.writeAscii("\nendobj\n")
  }

  private fun pdfNumber(value: Float): String {
    val rounded = value.roundToInt()
    if (abs(value - rounded) < 0.0001f) {
      return rounded.toString()
    }
    return String.format(Locale.US, "%.4f", value)
  }

  private fun targetDpi(value: Double?, pageSize: PageSize?): Double? {
    if (pageSize == null) {
      require(value == null) {
        "targetDpi requires explicit PDF page dimensions."
      }
      return null
    }

    val resolved = value ?: DEFAULT_TARGET_DPI
    require(resolved.isFinite() && resolved > 0) {
      "targetDpi must be a finite positive number."
    }
    return resolved
  }

  private fun jpegQuality(value: Double?): Int? {
    if (value == null) {
      return null
    }
    require(value.isFinite() && value >= 0 && value <= 1) {
      "jpegQuality must be a finite number from 0 to 1."
    }
    return (value * 100).roundToInt()
  }

  private fun pageSize(
    width: Double?,
    height: Double?,
    aspectRatio: Double?
  ): PageSize? {
    if (width == null && height == null) {
      require(aspectRatio == null) {
        "pageAspectRatio requires exactly one of pageWidth or pageHeight."
      }
      return null
    }

    if (width != null && height != null) {
      require(aspectRatio == null) {
        "pageAspectRatio cannot be combined with both pageWidth and pageHeight."
      }
      return PageSize(pagePoint(width, "pageWidth"), pagePoint(height, "pageHeight"))
    }

    val ratio = aspectRatio
    require(ratio != null && ratio.isFinite() && ratio > 0) {
      "pageAspectRatio must be a finite positive width-to-height ratio when only one page dimension is provided."
    }

    return if (width != null) {
      PageSize(pagePoint(width, "pageWidth"), pagePoint(width / ratio, "derived pageHeight"))
    } else {
      PageSize(pagePoint(height!! * ratio, "derived pageWidth"), pagePoint(height, "pageHeight"))
    }
  }

  private fun pagePoint(value: Double, label: String): Int {
    require(
      value.isFinite() && value >= 1.0 && value <= Int.MAX_VALUE
    ) {
      "$label must be finite positive PDF points."
    }

    val roundedValue = value.roundToInt()
    require(roundedValue > 0) {
      "$label must round to at least one PDF point."
    }
    return roundedValue
  }

  private fun destinationRect(
    imageWidth: Int,
    imageHeight: Int,
    pageWidth: Int,
    pageHeight: Int,
    imageFit: ImageFit
  ): RectF {
    val sourceWidth = imageWidth.toFloat()
    val sourceHeight = imageHeight.toFloat()
    val targetWidth = pageWidth.toFloat()
    val targetHeight = pageHeight.toFloat()

    return when (imageFit) {
      ImageFit.NONE -> RectF(
        (targetWidth - sourceWidth) / 2f,
        (targetHeight - sourceHeight) / 2f,
        (targetWidth + sourceWidth) / 2f,
        (targetHeight + sourceHeight) / 2f
      )
      ImageFit.FILL -> RectF(0f, 0f, targetWidth, targetHeight)
      ImageFit.CONTAIN, ImageFit.COVER -> {
        val widthRatio = targetWidth / sourceWidth
        val heightRatio = targetHeight / sourceHeight
        val scale = if (imageFit == ImageFit.CONTAIN) {
          min(widthRatio, heightRatio)
        } else {
          max(widthRatio, heightRatio)
        }
        val destinationWidth = sourceWidth * scale
        val destinationHeight = sourceHeight * scale
        RectF(
          (targetWidth - destinationWidth) / 2f,
          (targetHeight - destinationHeight) / 2f,
          (targetWidth + destinationWidth) / 2f,
          (targetHeight + destinationHeight) / 2f
        )
      }
    }
  }

  private fun localFile(value: String, label: String): File {
    if (value.isBlank()) {
      throw IllegalArgumentException("$label cannot be empty.")
    }

    val uri = Uri.parse(value)
    val scheme = uri.scheme
    val path = when {
      scheme == null -> value
      scheme.equals("file", ignoreCase = true) -> {
        if (!uri.host.isNullOrEmpty() && !uri.host.equals("localhost", ignoreCase = true)) {
          throw IllegalArgumentException("$label must refer to a local file.")
        }
        uri.path ?: throw IllegalArgumentException("$label is not a valid file URI.")
      }
      else -> throw IllegalArgumentException(
        "$label uses unsupported URI scheme '$scheme'; only local paths and file:// URIs are supported."
      )
    }

    if (!path.startsWith(File.separator)) {
      throw IllegalArgumentException("$label must be an absolute local path or file:// URI.")
    }

    return try {
      File(path).canonicalFile
    } catch (error: Exception) {
      throw IllegalArgumentException("$label is not a valid local path.", error)
    }
  }

  private fun validateOutputFile(file: File) {
    val parent = file.parentFile
      ?: throw IllegalArgumentException("Output path '${file.path}' has no parent directory.")

    if (!parent.isDirectory) {
      throw IllegalArgumentException("Output directory '${parent.path}' does not exist.")
    }
    if (!parent.canWrite()) {
      throw IllegalArgumentException("Output directory '${parent.path}' is not writable.")
    }
    if (file.exists() && (file.isDirectory || !file.canWrite())) {
      throw IllegalArgumentException("Output path '${file.path}' is not writable.")
    }
  }
}

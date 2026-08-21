import Foundation
import ImageIO
import NitroModules
import UIKit

final class HybridImagesToPdf: HybridImagesToPdfSpec {
  private static let defaultJpegQuality: CGFloat = 0.72
  private static let defaultTargetDpi: CGFloat = 200

  private struct PdfPageImage {
    let jpegData: Data
    let pixelWidth: Int
    let pixelHeight: Int
    let exifTransform: ExifPdfTransform
    let pageBounds: CGRect
    let destination: CGRect
  }

  private final class PdfOutput {
    private let handle: FileHandle
    private(set) var position = 0
    private var isClosed = false

    init(url: URL) throws {
      guard FileManager.default.createFile(atPath: url.path, contents: nil) else {
        throw RuntimeError.error(
          withMessage: "Could not create temporary PDF at '\(url.path)'."
        )
      }
      handle = try FileHandle(forWritingTo: url)
    }

    func write(_ data: Data) throws {
      try handle.write(contentsOf: data)
      position += data.count
    }

    func writeAscii(_ value: String) throws {
      try write(value.data(using: .ascii)!)
    }

    func close() throws {
      guard !isClosed else { return }
      try handle.close()
      isClosed = true
    }
  }

  func createPdf(options: CreatePdfOptions) throws -> Promise<String> {
    return Promise.parallel {
      let pageSize = try Self.pageSize(
        width: options.pageWidth,
        height: options.pageHeight,
        aspectRatio: options.pageAspectRatio
      )
      let targetDpi = try Self.targetDpi(options.targetDpi, pageSize: pageSize)
      let jpegQuality = try Self.jpegQuality(options.jpegQuality)
      let outputURL = try Self.localFileURL(options.outputPath, label: "Output path")
      try Self.validateOutputURL(outputURL)
      let temporaryURL = outputURL.deletingLastPathComponent()
        .appendingPathComponent("\(outputURL.lastPathComponent).tmp")
      defer {
        try? FileManager.default.removeItem(at: temporaryURL)
      }

      try Self.writePdf(
        pages: options.pages,
        pageSize: pageSize,
        imageFit: options.imageFit,
        autoRotateExif: options.autoRotateExif ?? true,
        targetDpi: targetDpi,
        jpegQuality: jpegQuality,
        to: temporaryURL
      )

      do {
        if FileManager.default.fileExists(atPath: outputURL.path) {
          _ = try FileManager.default.replaceItemAt(outputURL, withItemAt: temporaryURL)
        } else {
          try FileManager.default.moveItem(at: temporaryURL, to: outputURL)
        }
      } catch {
        throw RuntimeError.error(
          withMessage: "Could not write PDF to '\(outputURL.path)': \(error.localizedDescription)"
        )
      }

      return outputURL.absoluteString
    }
  }

  private static func writePdf(
    pages: [String],
    pageSize: CGSize?,
    imageFit: ImageFit?,
    autoRotateExif: Bool,
    targetDpi: CGFloat?,
    jpegQuality: CGFloat?,
    to outputURL: URL
  ) throws {
    guard !pages.isEmpty else {
      throw RuntimeError.error(withMessage: "No pages provided.")
    }

    let objectCount = 2 + pages.count * 3
    var offsets = Array(repeating: 0, count: objectCount + 1)
    let output = try PdfOutput(url: outputURL)
    defer { try? output.close() }

    try output.writeAscii("%PDF-1.4\n")
    try output.write(Data([0x25, 0xE2, 0xE3, 0xCF, 0xD3, 0x0A]))
    try appendObject(1, to: output, offsets: &offsets) {
      try $0.writeAscii("<< /Type /Catalog /Pages 2 0 R >>")
    }

    let pageReferences = pages.indices
      .map { "\(3 + $0 * 3) 0 R" }
      .joined(separator: " ")
    try appendObject(2, to: output, offsets: &offsets) {
      try $0.writeAscii("<< /Type /Pages /Count \(pages.count) /Kids [\(pageReferences)] >>")
    }

    for (index, imagePath) in pages.enumerated() {
      try autoreleasepool {
        let page = try processImage(
          imagePath,
          pageSize: pageSize,
          imageFit: imageFit,
          autoRotateExif: autoRotateExif,
          targetDpi: targetDpi,
          jpegQuality: jpegQuality
        )
        try writePage(page, index: index, to: output, offsets: &offsets)
      }
    }

    let xrefOffset = output.position
    try output.writeAscii("xref\n0 \(objectCount + 1)\n")
    try output.writeAscii("0000000000 65535 f \n")
    for objectNumber in 1...objectCount {
      try output.writeAscii("\(paddedOffset(offsets[objectNumber])) 00000 n \n")
    }
    try output.writeAscii(
      "trailer\n<< /Size \(objectCount + 1) /Root 1 0 R >>\nstartxref\n\(xrefOffset)\n%%EOF\n"
    )
    try output.close()
  }

  private static func processImage(
    _ imagePath: String,
    pageSize: CGSize?,
    imageFit: ImageFit?,
    autoRotateExif: Bool,
    targetDpi: CGFloat?,
    jpegQuality: CGFloat?
  ) throws -> PdfPageImage {
    let imageURL = try localFileURL(imagePath, label: "Image path")
    guard FileManager.default.isReadableFile(atPath: imageURL.path),
          let imageSource = CGImageSourceCreateWithURL(imageURL as CFURL, nil),
          let properties = CGImageSourceCopyPropertiesAtIndex(imageSource, 0, nil)
            as? [CFString: Any],
          let rawWidth = (properties[kCGImagePropertyPixelWidth] as? NSNumber)?.intValue,
          let rawHeight = (properties[kCGImagePropertyPixelHeight] as? NSNumber)?.intValue,
          rawWidth > 0,
          rawHeight > 0 else {
      throw RuntimeError.error(
        withMessage: "Image path '\(imageURL.path)' cannot be decoded into an image."
      )
    }

    let exifTransform = ExifPdfTransform(
      orientation: autoRotateExif
        ? (properties[kCGImagePropertyOrientation] as? NSNumber)?.intValue ?? 1
        : 1
    )
    let sourceWidth = exifTransform.swapsDimensions ? rawHeight : rawWidth
    let sourceHeight = exifTransform.swapsDimensions ? rawWidth : rawHeight
    let pageBounds = CGRect(
      x: 0,
      y: 0,
      width: pageSize?.width ?? CGFloat(sourceWidth),
      height: pageSize?.height ?? CGFloat(sourceHeight)
    )
    let fit = pageSize == nil ? ImageFit.none : (imageFit ?? .none)
    let destination = destinationRect(
      imageWidth: CGFloat(sourceWidth),
      imageHeight: CGFloat(sourceHeight),
      pageBounds: pageBounds,
      imageFit: fit
    )
    let cropSize: (width: CGFloat, height: CGFloat)?
    if fit == .cover {
      let scale = max(
        pageBounds.width / CGFloat(sourceWidth),
        pageBounds.height / CGFloat(sourceHeight)
      )
      cropSize = (
        width: pageBounds.width / scale,
        height: pageBounds.height / scale
      )
    } else {
      cropSize = nil
    }
    let encodedSourceWidth = max(1, Int((cropSize?.width ?? CGFloat(sourceWidth)).rounded()))
    let encodedSourceHeight = max(1, Int((cropSize?.height ?? CGFloat(sourceHeight)).rounded()))
    let renderedDestination = fit == .cover ? pageBounds : destination
    let targetSize = targetPixelSize(
      sourceWidth: encodedSourceWidth,
      sourceHeight: encodedSourceHeight,
      pageBounds: pageBounds,
      destination: renderedDestination,
      imageFit: fit,
      targetDpi: targetDpi
    )

    let sourceType = CGImageSourceGetType(imageSource)
    let isRgbJpeg = sourceType.map { String(describing: $0) == "public.jpeg" } == true
      && (properties[kCGImagePropertyColorModel] as? String) == "RGB"
    if jpegQuality == nil,
       isRgbJpeg,
       cropSize == nil,
       targetSize.width == sourceWidth,
       targetSize.height == sourceHeight {
      let jpegData: Data
      do {
        jpegData = try Data(contentsOf: imageURL, options: [.mappedIfSafe])
      } catch {
        throw RuntimeError.error(
          withMessage: "Image path '\(imageURL.path)' could not be read."
        )
      }
      return PdfPageImage(
        jpegData: jpegData,
        pixelWidth: rawWidth,
        pixelHeight: rawHeight,
        exifTransform: exifTransform,
        pageBounds: pageBounds,
        destination: renderedDestination
      )
    }

    let decodeScale = min(
      1,
      max(
        CGFloat(targetSize.width) / CGFloat(sourceWidth),
        CGFloat(targetSize.height) / CGFloat(sourceHeight)
      )
    )
    let thumbnailOptions: [CFString: Any] = [
      kCGImageSourceCreateThumbnailFromImageAlways: true,
      kCGImageSourceCreateThumbnailWithTransform: autoRotateExif,
      kCGImageSourceThumbnailMaxPixelSize: max(
        1,
        Int((CGFloat(max(sourceWidth, sourceHeight)) * decodeScale).rounded(.up))
      ),
      kCGImageSourceShouldCacheImmediately: true,
    ]

    guard let thumbnail = CGImageSourceCreateThumbnailAtIndex(
      imageSource,
      0,
      thumbnailOptions as CFDictionary
    ) else {
      throw RuntimeError.error(
        withMessage: "Image path '\(imageURL.path)' could not be resized."
      )
    }

    let renderSize = CGSize(width: targetSize.width, height: targetSize.height)
    let format = UIGraphicsImageRendererFormat()
    format.opaque = true
    format.scale = 1
    let renderer = UIGraphicsImageRenderer(size: renderSize, format: format)
    let logicalThumbnailWidth = thumbnail.width
    let logicalThumbnailHeight = thumbnail.height
    let drawRect: CGRect
    if let cropSize {
      let cropWidth = max(
        1,
        min(
          logicalThumbnailWidth,
          Int((CGFloat(logicalThumbnailWidth) * cropSize.width / CGFloat(sourceWidth)).rounded())
        )
      )
      let cropHeight = max(
        1,
        min(
          logicalThumbnailHeight,
          Int((CGFloat(logicalThumbnailHeight) * cropSize.height / CGFloat(sourceHeight)).rounded())
        )
      )
      let cropLeft = CGFloat(logicalThumbnailWidth - cropWidth) / 2
      let cropTop = CGFloat(logicalThumbnailHeight - cropHeight) / 2
      let scaleX = renderSize.width / CGFloat(cropWidth)
      let scaleY = renderSize.height / CGFloat(cropHeight)
      drawRect = CGRect(
        x: -cropLeft * scaleX,
        y: -cropTop * scaleY,
        width: CGFloat(logicalThumbnailWidth) * scaleX,
        height: CGFloat(logicalThumbnailHeight) * scaleY
      )
    } else {
      drawRect = CGRect(origin: .zero, size: renderSize)
    }
    let renderImage = UIImage(cgImage: thumbnail)
    let jpegData = renderer.jpegData(
      withCompressionQuality: jpegQuality ?? Self.defaultJpegQuality
    ) { context in
      UIColor.white.setFill()
      context.fill(CGRect(origin: .zero, size: renderSize))
      context.cgContext.interpolationQuality = .high
      renderImage.draw(in: drawRect)
    }

    return PdfPageImage(
      jpegData: jpegData,
      pixelWidth: targetSize.width,
      pixelHeight: targetSize.height,
      exifTransform: .identity,
      pageBounds: pageBounds,
      destination: renderedDestination
    )
  }

  private static func targetPixelSize(
    sourceWidth: Int,
    sourceHeight: Int,
    pageBounds: CGRect,
    destination: CGRect,
    imageFit: ImageFit,
    targetDpi: CGFloat?
  ) -> (width: Int, height: Int) {
    guard let targetDpi else {
      return (sourceWidth, sourceHeight)
    }

    let pixelsPerPoint = targetDpi / 72
    if imageFit == .fill {
      return (
        cappedPixelDimension(
          sourcePixels: sourceWidth,
          pagePoints: pageBounds.width,
          pixelsPerPoint: pixelsPerPoint
        ),
        cappedPixelDimension(
          sourcePixels: sourceHeight,
          pagePoints: pageBounds.height,
          pixelsPerPoint: pixelsPerPoint
        )
      )
    }

    let widthScale = abs(destination.width) * pixelsPerPoint / CGFloat(sourceWidth)
    let heightScale = abs(destination.height) * pixelsPerPoint / CGFloat(sourceHeight)
    let scale = min(1, max(widthScale, heightScale))
    return (
      max(1, Int((CGFloat(sourceWidth) * scale).rounded())),
      max(1, Int((CGFloat(sourceHeight) * scale).rounded()))
    )
  }

  private static func cappedPixelDimension(
    sourcePixels: Int,
    pagePoints: CGFloat,
    pixelsPerPoint: CGFloat
  ) -> Int {
    let desiredPixels = pagePoints * pixelsPerPoint
    guard desiredPixels.isFinite else {
      return sourcePixels
    }

    let roundedPixels = desiredPixels.rounded()
    if roundedPixels >= CGFloat(sourcePixels) || roundedPixels >= CGFloat(Int.max) {
      return sourcePixels
    }

    return max(1, Int(roundedPixels))
  }

  private static func writePage(
    _ page: PdfPageImage,
    index: Int,
    to output: PdfOutput,
    offsets: inout [Int]
  ) throws {
    let pageObject = 3 + index * 3
    let contentObject = pageObject + 1
    let imageObject = pageObject + 2
    let pageWidth = pdfNumber(page.pageBounds.width)
    let pageHeight = pdfNumber(page.pageBounds.height)
    let imageMatrix = page.exifTransform.pdfMatrix(
      pixelWidth: page.pixelWidth,
      pixelHeight: page.pixelHeight,
      destination: page.destination
    )

    try appendObject(pageObject, to: output, offsets: &offsets) {
      try $0.writeAscii(
        "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 \(pageWidth) \(pageHeight)] "
          + "/Resources << /XObject << /Im0 \(imageObject) 0 R >> >> "
          + "/Contents \(contentObject) 0 R >>"
      )
    }

    let commands = "q\n0 0 \(pageWidth) \(pageHeight) re W n\n"
      + "\(pdfNumber(imageMatrix.a)) \(pdfNumber(imageMatrix.b)) "
      + "\(pdfNumber(imageMatrix.c)) \(pdfNumber(imageMatrix.d)) "
      + "\(pdfNumber(imageMatrix.e)) \(pdfNumber(imageMatrix.f)) cm\n"
      + "/Im0 Do\nQ\n"
    let commandData = commands.data(using: .ascii)!
    try appendObject(contentObject, to: output, offsets: &offsets) {
      try $0.writeAscii("<< /Length \(commandData.count) >>\nstream\n")
      try $0.write(commandData)
      try $0.writeAscii("endstream")
    }

    try appendObject(imageObject, to: output, offsets: &offsets) {
      try $0.writeAscii(
        "<< /Type /XObject /Subtype /Image /Width \(page.pixelWidth) "
          + "/Height \(page.pixelHeight) /ColorSpace /DeviceRGB "
          + "/BitsPerComponent 8 /Interpolate true /Filter /DCTDecode "
          + "/Length \(page.jpegData.count) >>\nstream\n"
      )
      try $0.write(page.jpegData)
      try $0.writeAscii("\nendstream")
    }
  }

  private static func appendObject(
    _ number: Int,
    to output: PdfOutput,
    offsets: inout [Int],
    body: (PdfOutput) throws -> Void
  ) throws {
    offsets[number] = output.position
    try output.writeAscii("\(number) 0 obj\n")
    try body(output)
    try output.writeAscii("\nendobj\n")
  }

  private static func paddedOffset(_ offset: Int) -> String {
    let value = String(offset)
    return String(repeating: "0", count: max(0, 10 - value.count)) + value
  }

  private static func pdfNumber(_ value: CGFloat) -> String {
    let rounded = value.rounded()
    if abs(value - rounded) < 0.0001 {
      return String(Int(rounded))
    }
    return String(
      format: "%.4f",
      locale: Locale(identifier: "en_US_POSIX"),
      Double(value)
    )
  }

  private static func targetDpi(_ value: Double?, pageSize: CGSize?) throws -> CGFloat? {
    guard pageSize != nil else {
      guard value == nil else {
        throw RuntimeError.error(
          withMessage: "targetDpi requires explicit PDF page dimensions."
        )
      }
      return nil
    }

    let value = value ?? Double(defaultTargetDpi)
    guard value.isFinite, value > 0 else {
      throw RuntimeError.error(
        withMessage: "targetDpi must be a finite positive number."
      )
    }
    return CGFloat(value)
  }

  private static func jpegQuality(_ value: Double?) throws -> CGFloat? {
    guard let value else {
      return nil
    }
    guard value.isFinite, value >= 0, value <= 1 else {
      throw RuntimeError.error(
        withMessage: "jpegQuality must be a finite number from 0 to 1."
      )
    }
    return CGFloat(value)
  }

  private static func pageSize(
    width: Double?,
    height: Double?,
    aspectRatio: Double?
  ) throws -> CGSize? {
    guard width != nil || height != nil else {
      guard aspectRatio == nil else {
        throw RuntimeError.error(
          withMessage: "pageAspectRatio requires exactly one of pageWidth or pageHeight."
        )
      }
      return nil
    }

    if width != nil && height != nil {
      guard aspectRatio == nil else {
        throw RuntimeError.error(
          withMessage: "pageAspectRatio cannot be combined with both pageWidth and pageHeight."
        )
      }

      return CGSize(
        width: try pagePoint(width!),
        height: try pagePoint(height!)
      )
    }

    guard let aspectRatio,
          aspectRatio.isFinite,
          aspectRatio > 0 else {
      throw RuntimeError.error(
        withMessage: "pageAspectRatio must be a finite positive width-to-height ratio when only one page dimension is provided."
      )
    }

    if let width {
      return CGSize(
        width: try pagePoint(width),
        height: try pagePoint(width / aspectRatio)
      )
    }

    let height = height!
    return CGSize(
      width: try pagePoint(height * aspectRatio),
      height: try pagePoint(height)
    )
  }

  private static func pagePoint(_ value: Double) throws -> CGFloat {
    guard value.isFinite, value >= 1 else {
      throw RuntimeError.error(
        withMessage: "Page dimensions must be finite positive PDF points."
      )
    }

    let roundedValue = value.rounded()
    guard roundedValue >= 1 else {
      throw RuntimeError.error(
        withMessage: "Page dimensions must round to at least one PDF point."
      )
    }
    return CGFloat(roundedValue)
  }

  private static func destinationRect(
    imageWidth: CGFloat,
    imageHeight: CGFloat,
    pageBounds: CGRect,
    imageFit: ImageFit
  ) -> CGRect {
    switch imageFit {
      case .none:
        return CGRect(
          x: pageBounds.midX - imageWidth / 2,
          y: pageBounds.midY - imageHeight / 2,
          width: imageWidth,
          height: imageHeight
        )
      case .fill:
        return pageBounds
      case .contain, .cover:
        let widthRatio = pageBounds.width / imageWidth
        let heightRatio = pageBounds.height / imageHeight
        let scale: CGFloat
        switch imageFit {
          case .contain:
            scale = min(widthRatio, heightRatio)
          case .cover:
            scale = max(widthRatio, heightRatio)
          default:
            scale = 1
        }
        let destinationWidth = imageWidth * scale
        let destinationHeight = imageHeight * scale
        return CGRect(
          x: pageBounds.midX - destinationWidth / 2,
          y: pageBounds.midY - destinationHeight / 2,
          width: destinationWidth,
          height: destinationHeight
        )
    }
  }

  private static func localFileURL(_ value: String, label: String) throws -> URL {
    guard !value.isEmpty else {
      throw RuntimeError.error(withMessage: label + " cannot be empty.")
    }

    if let parsedURL = URL(string: value), let scheme = parsedURL.scheme {
      guard scheme.caseInsensitiveCompare("file") == .orderedSame,
            parsedURL.isFileURL,
            parsedURL.host == nil || parsedURL.host?.caseInsensitiveCompare("localhost") == .orderedSame,
            !parsedURL.path.isEmpty else {
        throw RuntimeError.error(
          withMessage: label + " uses an unsupported URI; only local paths and file:// URIs are supported."
        )
      }

      return URL(fileURLWithPath: parsedURL.path).standardizedFileURL
    }

    guard value.hasPrefix("/") else {
      throw RuntimeError.error(
        withMessage: label + " must be an absolute local path or file:// URI."
      )
    }

    return URL(fileURLWithPath: value).standardizedFileURL
  }

  private static func validateOutputURL(_ url: URL) throws {
    let fileManager = FileManager.default
    let parentURL = url.deletingLastPathComponent()
    var isDirectory: ObjCBool = false

    guard fileManager.fileExists(atPath: parentURL.path, isDirectory: &isDirectory),
          isDirectory.boolValue else {
      throw RuntimeError.error(
        withMessage: "Output directory '\(parentURL.path)' does not exist."
      )
    }

    guard fileManager.isWritableFile(atPath: parentURL.path) else {
      throw RuntimeError.error(
        withMessage: "Output directory '\(parentURL.path)' is not writable."
      )
    }

    var outputIsDirectory: ObjCBool = false
    if fileManager.fileExists(atPath: url.path, isDirectory: &outputIsDirectory),
       outputIsDirectory.boolValue || !fileManager.isWritableFile(atPath: url.path) {
      throw RuntimeError.error(
        withMessage: "Output path '\(url.path)' is not writable."
      )
    }
  }
}

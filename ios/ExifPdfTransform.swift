import CoreGraphics

struct PdfMatrix {
  let a: CGFloat
  let b: CGFloat
  let c: CGFloat
  let d: CGFloat
  let e: CGFloat
  let f: CGFloat
}

struct ExifPdfTransform {
  static let identity = ExifPdfTransform(orientation: 1)

  let orientation: Int

  init(orientation: Int) {
    self.orientation = (1...8).contains(orientation) ? orientation : 1
  }

  var swapsDimensions: Bool {
    orientation >= 5 && orientation <= 8
  }

  func pdfMatrix(
    pixelWidth: Int,
    pixelHeight: Int,
    destination: CGRect
  ) -> PdfMatrix {
    let orientedWidth = swapsDimensions ? pixelHeight : pixelWidth
    let orientedHeight = swapsDimensions ? pixelWidth : pixelHeight
    let scaleX = destination.width / CGFloat(orientedWidth)
    let scaleY = destination.height / CGFloat(orientedHeight)
    let transform: PdfMatrix
    switch orientation {
      case 2:
        transform = PdfMatrix(a: -1, b: 0, c: 0, d: 1, e: CGFloat(pixelWidth), f: 0)
      case 3:
        transform = PdfMatrix(
          a: -1, b: 0, c: 0, d: -1,
          e: CGFloat(pixelWidth), f: CGFloat(pixelHeight)
        )
      case 4:
        transform = PdfMatrix(a: 1, b: 0, c: 0, d: -1, e: 0, f: CGFloat(pixelHeight))
      case 5:
        transform = PdfMatrix(a: 0, b: 1, c: 1, d: 0, e: 0, f: 0)
      case 6:
        transform = PdfMatrix(a: 0, b: 1, c: -1, d: 0, e: CGFloat(pixelHeight), f: 0)
      case 7:
        transform = PdfMatrix(
          a: 0, b: -1, c: -1, d: 0,
          e: CGFloat(pixelHeight), f: CGFloat(pixelWidth)
        )
      case 8:
        transform = PdfMatrix(a: 0, b: -1, c: 1, d: 0, e: 0, f: CGFloat(pixelWidth))
      default:
        transform = PdfMatrix(a: 1, b: 0, c: 0, d: 1, e: 0, f: 0)
    }

    // PDF image space has a top-left origin; PDF page space has a bottom-left origin.
    return PdfMatrix(
      // PDF image XObjects are drawn in a normalized unit square. Convert the
      // pixel-space orientation matrix into that coordinate system before
      // applying the destination rectangle.
      a: scaleX * transform.a * CGFloat(pixelWidth),
      b: -scaleY * transform.b * CGFloat(pixelWidth),
      c: -scaleX * transform.c * CGFloat(pixelHeight),
      d: scaleY * transform.d * CGFloat(pixelHeight),
      e: scaleX * (transform.e + transform.c * CGFloat(pixelHeight)) + destination.minX,
      f: destination.minY + destination.height
        - scaleY * (transform.f + transform.d * CGFloat(pixelHeight))
    )
  }
}

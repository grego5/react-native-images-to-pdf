import type { HybridObject } from 'react-native-nitro-modules'

export type ImageFit = 'none' | 'fill' | 'contain' | 'cover'

export interface CreatePdfOptions {
  outputPath: string
  pages: string[]
  imageFit?: ImageFit
  /** Whether to apply the image's EXIF orientation. Defaults to true. */
  autoRotateExif?: boolean
  /** JPEG encoding quality from 0 to 1. Defaults to 0.72 when encoding is required. */
  jpegQuality?: number
  /** Maximum rendered image resolution in pixels per inch. Defaults to 200 with a page size. */
  targetDpi?: number
  /** Global PDF page width in points. Use with pageHeight or pageAspectRatio. */
  pageWidth?: number
  /** Global PDF page height in points. Use with pageWidth or pageAspectRatio. */
  pageHeight?: number
  /** Global page width/height ratio. Use with exactly one page dimension. */
  pageAspectRatio?: number
}

export interface ImagesToPdf extends HybridObject<{
  ios: 'swift'
  android: 'kotlin'
}> {
  createPdf(options: CreatePdfOptions): Promise<string>
}

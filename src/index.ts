import { NitroModules } from 'react-native-nitro-modules'
import type {
  CreatePdfOptions as NativeCreatePdfOptions,
  ImageFit,
  ImagesToPdf,
} from './ImagesToPdf.nitro'

export type { ImageFit }

type CommonCreatePdfOptions = Pick<
  NativeCreatePdfOptions,
  'outputPath' | 'pages' | 'imageFit' | 'autoRotateExif' | 'jpegQuality'
>

export type SourceSizedCreatePdfOptions = CommonCreatePdfOptions & {
  targetDpi?: never
  pageWidth?: never
  pageHeight?: never
  pageAspectRatio?: never
}

export type FixedSizeCreatePdfOptions = CommonCreatePdfOptions & {
  targetDpi?: number
  pageWidth: number
  pageHeight: number
  pageAspectRatio?: never
}

export type WidthWithAspectRatioCreatePdfOptions = CommonCreatePdfOptions & {
  targetDpi?: number
  pageWidth: number
  pageHeight?: never
  pageAspectRatio: number
}

export type HeightWithAspectRatioCreatePdfOptions = CommonCreatePdfOptions & {
  targetDpi?: number
  pageWidth?: never
  pageHeight: number
  pageAspectRatio: number
}

export type CreatePdfOptions =
  | SourceSizedCreatePdfOptions
  | FixedSizeCreatePdfOptions
  | WidthWithAspectRatioCreatePdfOptions
  | HeightWithAspectRatioCreatePdfOptions

const imagesToPdf = NitroModules.createHybridObject<ImagesToPdf>('ImagesToPdf')

export function createPdf(options: SourceSizedCreatePdfOptions): Promise<string>
export function createPdf(options: FixedSizeCreatePdfOptions): Promise<string>
export function createPdf(options: WidthWithAspectRatioCreatePdfOptions): Promise<string>
export function createPdf(options: HeightWithAspectRatioCreatePdfOptions): Promise<string>
export function createPdf(options: CreatePdfOptions): Promise<string>
export function createPdf(options: NativeCreatePdfOptions): Promise<string> {
  return imagesToPdf.createPdf(options)
}

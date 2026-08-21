# react-native-images-to-pdf

A React Native Nitro Module for creating PDFs from local images.

## Compatibility

The current test target is:

- Expo SDK `57`
- React Native `0.86.2`
- React Native Nitro Modules `0.37.1`
- Android compile SDK `36` with NDK `27`
- iOS deployment target `13.4`

The package supports React Native `0.75+`; the current test target is React Native `0.86.2`. Android requires API `24+`.

## Example

```ts
import { File, Paths } from 'expo-file-system';
import { createPdf } from 'react-native-images-to-pdf';

const output = new File(Paths.cache, 'result.pdf');

const imagesArray = [
  'file:///data/user/0/com.example/cache/image1.jpeg',
  'file:///data/user/0/com.example/cache/image2.png',
  'file:///data/user/0/com.example/cache/image3.webp',
];

await createPdf({
  pages: imagesArray,
  outputPath: output.uri,
});

for (const imageUri of imagesArray) {
  new File(imageUri).delete();
}

return output; // file:///data/user/0/com.example/cache/result.pdf
```

### Options

```ts
type ImageFit = 'none' | 'fill' | 'contain' | 'cover';

type CreatePdfOptions =
  | {
      outputPath: string;
      pages: string[];
      imageFit?: ImageFit;
      autoRotateExif?: boolean;
      jpegQuality?: number;
    };
  | {
      outputPath: string;
      pages: string[];
      imageFit?: ImageFit;
      autoRotateExif?: boolean;
      jpegQuality?: number;
      targetDpi?: number;
      pageWidth: number;
      pageHeight: number;
    }
  | {
      outputPath: string;
      pages: string[];
      imageFit?: ImageFit;
      autoRotateExif?: boolean;
      jpegQuality?: number;
      targetDpi?: number;
      pageWidth: number;
      pageAspectRatio: number;
    }
  | {
      outputPath: string;
      pages: string[];
      imageFit?: ImageFit;
      autoRotateExif?: boolean;
      jpegQuality?: number;
      targetDpi?: number;
      pageHeight: number;
      pageAspectRatio: number;
    };


    const defaults = {
      jpegQuality: 0.72,
      autoRotateExif: true,
      imageFit: 'none',
      targetDpi: 200 // when pageWidth/pageHeight are provided
    };
```

- `outputPath` — absolute output path or `file://` URI. file:///data/user/0/com.example/cache/result.pdf or /data/user/0/com.example/cache/result.pdf
- `pages` — non-empty array of absolute image paths or `file://` URIs.
- `imageFit` — global image placement mode. Defaults to `none`.
- `autoRotateExif` — whether to apply EXIF orientation metadata before rendering. Defaults to `true`; set to `false` to preserve the source pixel orientation.
- `jpegQuality` — native JPEG encoding quality from `0` to `1`. If omitted, eligible RGB JPEGs are passed through unchanged; images that require encoding use `0.72`. If provided, JPEGs are re-encoded at the requested quality.
- `targetDpi` — maximum rendered image resolution. Defaults to `200` when page dimensions are available and never upscales an image.
- `pageWidth` and `pageHeight` — global page dimensions in PDF points (`72` points per inch).
- `pageAspectRatio` — global `width / height` ratio, used with exactly one page dimension.

JPEG passthrough is used only when no pixel transformation is needed. Set `autoRotateExif: false` to ignore EXIF orientation and render the source pixels as stored. If page dimensions are omitted, each page uses the source image dimensions, `targetDpi` is unavailable, and `imageFit` has no visible effect.

Orientation is resolved before layout. With `autoRotateExif: true`, the displayed width and height include EXIF rotation, so `imageFit`, `cover` cropping, target-DPI calculations, and the default page size all operate on the upright image. `pageAspectRatio` only derives the PDF page dimensions; it does not rotate or resize source pixels by itself.

Explicit page dimensions or `targetDpi` do not automatically force re-encoding. DPI is a maximum and never upscales: if the source is already at or below the calculated target, an eligible RGB JPEG can still be passed through unchanged and scaled by the PDF placement matrix. Pixel cropping, downscaling, alpha flattening, non-JPEG input, or an explicit `jpegQuality` requires JPEG encoding.

Input decoding uses native platform codecs. JPEG, PNG, and WebP are recommended; HEIC/HEIF, GIF (first frame), BMP, and TIFF depend on platform support. SVG, PDF, RAW, remote URLs, and `content://` URIs are not supported.

### Image fitting

- `none` — center the image at its native size; it may extend beyond the page.
- `fill` — stretch the image to fill the page.
- `contain` — preserve the aspect ratio and fit the complete image inside the page.
- `cover` — preserve the aspect ratio and fill the page, cropping overflow before encoding.

For example, an A4 page can be created with either both dimensions:

```ts
await createPdf({
  outputPath: '/absolute/path/a4.pdf',
  pages: imagePaths,
  pageWidth: 595,
  pageHeight: 842,
  imageFit: 'contain',
});
```

or one dimension and an aspect ratio:

```ts
await createPdf({
  outputPath: '/absolute/path/a4.pdf',
  pages: imagePaths,
  pageWidth: 595,
  pageAspectRatio: 595 / 842,
  imageFit: 'contain',
});
```

`targetDpi` is converted from PDF points to pixels with `points / 72 * DPI`. The default produces an A4 page of approximately `1653 × 2339` pixels. Images smaller than the calculated target retain their source resolution.

The DPI cap follows the rendered image rectangle. Use `contain`, `cover`, or `fill` for page-relative DPI processing; `none` retains the image's native point dimensions.

## Errors and file handling

The promise rejects for empty page lists, invalid paths, unsupported URI schemes, unreadable images, invalid sizing or processing options, and output failures. Remote URLs, base64 data, and platform-specific provider URIs are not accepted; convert them to local files first.

The module creates or replaces the output file and does not delete input images. No temporary image files are created; PDF output is written to a temporary file beside the requested output and moved into place after successful completion.

## Processing and file behavior

The native pipeline is:

```text
JS options
  ↓ native value conversion
native snapshot of paths and options
  ↓
validate options and output directory
  ↓
create hidden temporary PDF beside the output
  ↓
for each page:
    read source image metadata
    ┌─ reuse eligible JPEG bytes
    │    or
    └─ decode and render orientation, crop, and scaling in one pass
         ↓ JPEG-encode when rendering was required
    append the JPEG as a PDF image object
    apply a PDF placement matrix to draw it at the page destination
    release page memory
  ↓
write the PDF cross-reference table and trailer
  ↓
close the temporary PDF
  ↓
rename or replace the temporary file as the final output
```

The `pages` array is copied into native values when the call crosses the Nitro boundary. Mutating the JavaScript array after calling `createPdf` does not change the pages being processed.
The native operation still processes the original `imageA` and `imageB` list. Native code does not remove, reorder, or replace entries in the array. Image files are also only read during processing; they are not modified by the module.

### Memory usage

Only one page is intentionally retained at a time:

```text
source file
  ↓
read source image metadata
  ├─ eligible JPEG with no pixel processing
  │    ↓
  │  original JPEG bytes
  └─ pixel processing required
       ↓
     decoded bitmap or CGImage
       ↓
     rendered/scaled bitmap
       ↓
     encoded JPEG buffer
  ↓
JPEG bytes embedded as a PDF image object
  ↓
PDF placement matrix draws the image on the page
  ↓
written into the temporary PDF
  ↓
page buffer released
```

The complete PDF is streamed to disk rather than accumulated in memory. Platform image codecs may still allocate temporary buffers, and one page can temporarily require both decoded image memory and encoded JPEG memory. An unusually large individual image can therefore still cause an allocation or out-of-memory failure, but memory does not intentionally grow with the number of pages.

### Temporary files and replacement

The temporary PDF is created beside the requested output so the final replacement remains on the same filesystem:

```text
/output/result.pdf
/output/result.pdf.tmp
```

If generation fails, cleanup removes the temporary file when possible. An existing output remains untouched, and no final output is created if one did not already exist. A force-terminated process can leave an orphaned temporary file.

After successful PDF generation:

```text
temporary.pdf → result.pdf       # output did not exist
temporary.pdf → replaces result.pdf  # output already existed
```

Replacing an existing output is intentional. It happens only after all pages, the cross-reference table, and the trailer have been written successfully. Concurrent calls using the same output path are not serialized; the call that finishes last determines the final file.

### Errors and surprising cases

The Promise can reject because of invalid native input, an empty page list, invalid sizing or quality options, unsupported URI schemes, missing or unreadable images, unsupported or corrupt image data, bitmap allocation failures, JPEG encoding failures, unwritable output directories, temporary-file failures, or final rename/replacement failures. Invalid Nitro argument conversion can throw before the Promise is created.

The following behaviors are intentional but worth keeping in mind:

- If `outputPath` equals an input image path, the image is read first and then replaced by the generated PDF at the end. Use a distinct output path to avoid destroying an input.
- Repeated paths in `pages` produce repeated PDF pages.
- `cover` crops the centered overflow before encoding, reducing unnecessary JPEG data while preserving standard cover behavior.
- `fill` stretches width and height independently and therefore does not preserve aspect ratio.
- Images that require processing are converted to JPEG. Processed images have transparency flattened against white, and original image metadata is not preserved, including EXIF orientation metadata.
- `autoRotateExif` defaults to `true`; when `false`, EXIF orientation is ignored and the source pixel orientation is preserved.
- `targetDpi` is a maximum and does not upscale small images.
- With `imageFit: 'none'`, an image can extend beyond the page and be clipped.
- Areas outside a `contain` image are not explicitly painted white in the PDF. Most viewers display them as white, but the PDF has no explicit page background.
- `targetDpi` values larger than needed to preserve source resolution are safely capped at the source dimensions and do not upscale images. Extremely large page dimensions or source images can still exceed platform PDF, graphics, or memory limits.


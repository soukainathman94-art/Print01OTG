# USB Print

Android print service that prints over a **USB OTG cable only**. No internet permission, no ads, no tracking.

## Build (no tools needed)
1. Create a repo on GitHub and upload the contents of this folder.
2. Open the **Actions** tab -> "Build APK" -> download the `UsbPrint-apk` artifact.
3. Unzip, install the APK.

(or open in Android Studio and press Run)

## Use
1. Connect the printer with a USB OTG cable.
2. Settings -> Printing -> **USB Print** -> turn on.
3. Any app -> Share / Print -> choose the USB printer. Accept the USB permission prompt.

## Preview screen
Open the app -> "Choose file to print" (PDF or image). The preview shows exactly the sheets that will come out
(same layout code is used for preview and printing). Pinch / double-tap to zoom the preview.
Controls: paper (A4/Letter/A5), orientation, fit-to-page or actual size, scale 10-400%, page range, copies,
color/B&W, duplex. If the content is larger than one sheet it continues on extra sheets and the sheet count updates live.

## How it works / limits
- Talks IPP-over-USB (USB class 7 / subclass 1 / protocol 4) and sends PWG Raster (or JPEG as a fallback).
- Works with printers that support IPP-over-USB (most models since ~2015, e.g. recent HP, Canon, Epson, Brother).
- Old printers that only accept proprietary languages (GDI / host-based) are NOT supported.

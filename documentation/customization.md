# Customize the update UI

## Built-in dialog

The stable SDK exposes `UpdateDialogOptions` for the dialog shown by
`checkAndShowUpdate()` or `launch()`.

```kotlin
zealot.setDialogOptions(
    UpdateDialogOptions(
        title = getString(R.string.update_title),
        updateButtonText = getString(R.string.update_action),
        laterButtonText = getString(R.string.update_later)
    )
).checkAndShowUpdate()
```

Import `im.ews.zealot.UpdateDialogOptions`. Omitted text uses Zealot's defaults.
The stable dialog ships English and Chinese strings. With `cancelable = false`, the dialog
ignores Back and outside taps while the **Later** action still dismisses it.
Pass `null` to restore the default dialog options.

Use `setUpdatePresenter()` to supply your own UI while Zealot handles the
check. The presenter runs on the main thread and receives a live Activity
and the release. Pass `null` to restore Zealot's dialog.

```kotlin
zealot.setUpdatePresenter { activity, release ->
    showUpdateScreen(activity, release)
}.checkAndShowUpdate()
```

## Update sheet

The sheet is available in [development builds](automatic-updates.md). Configure
it before calling `enableUpdatePrompts(downloadUpdates = true)`. The palette
and font can be set independently.

### Colors

Add a style to your app's resources.

```xml
<style name="AppUpdateSheet">
    <item name="zealotSheetAccent">@color/update_accent</item>
    <item name="zealotSheetOnAccent">@color/update_on_accent</item>
    <item name="zealotSheetNotesSurface">@color/update_notes_surface</item>
</style>
```

Call `zealot.setUpdateSheetTheme(R.style.AppUpdateSheet)`. Omitted colors come
from Zealot's light or dark palette. Use your app's `values-night` resources for
night colors. Pass `0` to restore the default palette.

| Attribute | Applies to |
| --- | --- |
| `zealotSheetSurface` | Sheet background |
| `zealotSheetNotesSurface` | Release notes background |
| `zealotSheetOnSurface` | Headings |
| `zealotSheetOnSurfaceVariant` | Supporting text, release notes, and download percentage |
| `zealotSheetAccent` | Primary button background, secondary actions, and progress indicator |
| `zealotSheetOnAccent` | Primary button text |
| `zealotSheetProgressTrack` | Progress track |
| `zealotSheetRipple` | Button press feedback |

### Typeface

Put a font in your app's `res/font` directory and call
`zealot.setUpdateSheetFont(R.font.brand_font)`. You can also pass a loaded
Android `Typeface` through `setUpdateSheetTypeface(typeface)`. The supplied font
family applies to headings, release notes, buttons, and the percentage label.
Use `setUpdateSheetFont(0)` or `setUpdateSheetTypeface(null)` to restore the
default. Both setters work from Java.

The compact status, standalone dialog and system installer have their own
appearance.

## Language

Development builds support English, Simplified Chinese, German, French, and
Russian. Zealot follows the Activity's locale and falls back to English when
Android cannot match one of the supported languages.

Set `zealot.setLocale(Locale.forLanguageTag("fr-FR"))` before starting a check
or attaching prompts. Pass `null` to follow the Activity's locale. To change
the language of an attached prompt, close its handle and attach it again.
Translated SDK strings leave user-provided labels and server release notes
as supplied. If the app filters resource languages, include each SDK language
you plan to display.

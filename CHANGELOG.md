# Changelog

All notable changes (starting from v1.7.3) to stable releases will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [v1.0.11] - 2026-09-24

### Español

- La APK release conserva los constructores que WorkManager necesita para iniciar la cola; los errores al arrancar se muestran en la tarea en lugar de dejarla pendiente.
- Los posts de Instagram prueban `/embed/` antes de la ruta anterior y recuperan la cola al abrir SeaRL.
- Los elementos pendientes ofrecen Reintentar. El post de 16 fotos reportado se verificó en un Android real.

### English

- The release APK keeps the constructors WorkManager needs to start the queue; startup failures appear on the task instead of leaving it pending.
- Instagram posts try `/embed/` before the previous endpoint and resume the queue when SeaRL opens.
- Pending items offer Retry. The reported 16-photo post was verified on a real Android device.

## [v1.0.10] - 2026-09-24

### Español

- Los posts de Instagram intentan primero la API con la sesión del WebView integrado; si no devuelve medios, usan el embed como respaldo.
- La solicitud al embed también incluye las cookies guardadas para Instagram.

### English

- Instagram posts try the API with the built-in WebView session first; if it returns no media, they fall back to the embed.
- Embed requests also include the saved Instagram cookies.

## [v1.0.9] - 2026-09-24

### Español

- Los posts públicos de Instagram con fotos o carruseles se leen desde el embed cuando la API de medios requiere iniciar sesión; cada foto se descarga en su formato original.
- La cola vuelve a procesar tareas pendientes al abrir la app y atiende las que se añaden mientras termina un worker.
- Las filas de la cola muestran un icono de plataforma en lugar del badge de texto; se redujo el tamaño de los iconos de plataforma.

### English

- Public Instagram photo posts and carousels use the embed when the media API requires sign-in; each photo downloads in its original format.
- The queue resumes pending tasks when the app opens and picks up tasks added while a worker finishes.
- Queue rows show a platform icon instead of a text badge; platform icons are smaller.

## [v1.0.8] - 2026-09-24

### Español

- Se rediseñó la navegación principal con una barra inferior para Inicio, Descargas y Ajustes, y se reorganizó la pantalla de descargas con filtros de estado.
- Se renovaron la pantalla de inicio, la presentación de ajustes y los iconos de plataformas en la cola y el historial. Se ajustó el icono adaptable de SeaRL para evitar el recorte del logo.
- Se mejoró el pegado de varios enlaces, incluso desde el teclado Samsung, para mantener cada URL en su propia línea.
- Se mejoró el tratamiento de enlaces compartidos de Facebook y Threads y la conservación de cookies de inicio de sesión.
- Se actualizó el yt-dlp integrado a la versión estable 2026.08.19; el canal estable es ahora el predeterminado para nuevas instalaciones.
- La actualización se distribuye en una sola APK universal compatible con las arquitecturas incluidas.

### English

- Redesigned the main navigation with a bottom bar for Home, Downloads, and Settings, and reorganized downloads with status filters.
- Refreshed the home screen, settings presentation, and platform icons in the queue and history. Adjusted SeaRL's adaptive launcher icon to keep the logo visible.
- Improved pasting multiple links, including from Samsung Keyboard, so each URL remains on its own line.
- Improved handling of Facebook and Threads share links and persistence of sign-in cookies.
- Updated the bundled yt-dlp to stable version 2026.08.19; stable is now the default update channel for new installs.
- The update is distributed as one universal APK for the bundled architectures.

## [v1.0.7] - 2026-07-19

### Español

- Se robusteció el pegado consecutivo desde el portapapeles y el teclado Samsung: cada enlace queda en su propia línea y el cursor avanza a una línea vacía.
- Se corrigió una carrera que podía dejar una descarga completada mostrando el estado “Descargando”.
- Los archivos nuevos se registran con fecha actual en MediaStore para aparecer al inicio de “Recientes” al compartirlos.
- Las publicaciones fotográficas de TikTok permiten elegir entre descargar las fotos originales o generar un video con su música, cuando TikTok proporciona la pista.
- Las fotos y stories de Instagram continúan descargándose en su formato original, sin conversión automática.
- Se agregó confirmación para enlaces descargados anteriormente, con opciones para volver a descargarlos realmente u omitirlos.
- Se documentaron nuevos bugs y limitaciones para darles seguimiento en futuras versiones.

### English

- Hardened consecutive pasting from the clipboard and Samsung Keyboard: every link stays on its own line and the cursor advances to an empty line.
- Fixed a race that could leave a completed download displayed as “Downloading”.
- New files are registered with current MediaStore timestamps so they appear first in Android's sharing “Recents”.
- TikTok photo posts can be downloaded as original photos or converted into a video with their music when TikTok exposes the audio track.
- Instagram photos and stories continue to download in their original format without automatic conversion.
- Added confirmation for previously downloaded links, allowing a real re-download or omission.
- Documented newly identified bugs and limitations for future releases.

## [v1.0.6] - 2026-06-29

### Español

- Se agregó inicio de sesión WebView para Facebook y exportación automática de cookies de Facebook/Meta.
- Se mejoró el pegado masivo de enlaces: cada URL queda en su propia línea y el cursor avanza correctamente.
- Se corrigió la apertura/reproducción de archivos descargados cuando Android cambia la forma de resolver la ruta.
- Se mejoró la detección de archivos descargados cuando yt-dlp cambia el nombre final.
- Se agregaron mensajes de error más claros y reportables para usuarios no técnicos.
- El diálogo de actualización ahora muestra los cambios en el idioma del dispositivo cuando el release incluye secciones localizadas.

### English

- Added Facebook WebView login and automatic Facebook/Meta cookie export.
- Improved bulk link pasting so each URL stays on its own line and the cursor moves correctly.
- Fixed opening/playing downloaded files when Android resolves file paths differently.
- Improved downloaded-file detection when yt-dlp changes the final filename.
- Added clearer, report-friendly download errors for non-technical users.
- The update dialog now shows changelog text in the device language when the release includes localized sections.

## [v1.0.5] - 2026-06-23

### Fixed

- Restored X/Twitter video downloads by using a Brave/Chromium-compatible mobile user agent for login and yt-dlp requests.
- Improved X/Twitter cookie handling across `x.com`, `twitter.com`, API, and mobile domains.
- Added explicit X/Twitter session validation for `auth_token` and `ct0` so missing-login failures are reported clearly.
- Improved WebView session persistence by reopening authenticated platform home pages instead of forcing login routes when valid cookies exist.
- Preserved Instagram author metadata in generated titles for direct media, stories, photos, and videos.
- Added automatic line breaks after pasted links without modifying the pasted URL.

### Changed

- X/Twitter cookie generation opens the login flow directly only when no authenticated session is detected.
- WebView cookie flushing now also runs when leaving the cookie WebView.

## [v2.0.0][2.0.0] - unreleased

### Notable changes from v1.13

- Concurrent downloading
- Download queue
- User interface overhaul
- Large screen support
- Resume failed/canceled download
- Backup & restore unfinished tasks in the download queue
- Select from formats/playlists in Quick Download
- Predictive back animation support for Android 14+
- Bump up minimum API level to 24 (Android 7.0)

## [v1.13.0][1.13.0] - 2024-08-18

### Fixed

- Fix the issue where exported command templates could not be imported in v1.12.x
- Fix an unexpected behavior where multiple formats would be selected

### Change

- Update `youtubedl-android` to v0.16.1
- Update translations

## [v1.12.1][1.12.1] - 2024-04-17

### Added

* Add auto update interval for yt-dlp
* Cookies page now shows the current count of cookies stored in the database

### Fixed

* Intercept non-HTTP(s) URLs opened in WebView
* Videos are remuxed to mkv even when download subtitle is disabled
* Use MD2 ModalBottomSheetLayout in devices on API < 30
* Block downloads when updating yt-dlp

### Known issues

* TextFields(IME) fallback to plain character mode when showing a ModalBottomSheet
* yt-dlp might be broken if you tried to download something while it was
  updating (`bad local file header`). To fix it, you just need to update yt-dlp again

## [v1.12.0][1.12.0] - 2024-04-05

### Added

* Search from download history
* Search from subtitles in format selection page
* Export download history to file/clipboard
* Import download history from file/clipboard
* Re-download unavailable videos
* Download auto-translated subtitles
* Remember subtitle selection for next downloads
* Remux videos into mkv container for better compatibility
* Configuration for not using the download type in the last download
* Improve UI/UX for download error handling
* Add splash screen
* Haptic feedback BZZZTT!!1!

### Changed

* Long pressing on an item in download history now selects it
* Use nightly builds for yt-dlp by default
* Migrate `Slider` & `ProgressIndicator` to the new visual styles in MD3
* Use default display name from system for locales
* Metadata of videos is also embedded in the files now
* A few UI changes that I forgot

### Fixed

* Fix a permission issue when using Seal in a different user profile or private space
* Fix an issue where the text cannot be copied in the menu of the download history
* Display approximate file size for formats when there's no exact value available
* Fix an issue causes app to crash when the selected template is not available
* Custom command now ignore empty URLs, which means you can insert URLs along with arguments in
  command templates
* Fix an issue where some formats may be unavailable when downloading playlists

### Known issues

* TextFields(IME) fallback to plain character mode when showing a ModalBottomSheet
* ModalBottomSheet handles insets incorrectly on devices below API 30

## [v1.11.3][1.11.3] - 2024-01-22

### Added

* Merge multiple audio streams into a single file
* Allow downloading with cellular network temporarily

### Fixed

* App creates duplicated command templates on initialization
* Cannot make video clip in FormatPage

## [v1.11.2][1.11.2] - 2024-01-06

### Added

* Keep subtitles files after embedding into videos
* Force all connections via ipv4
* Prefer vp9.2 if av1 hardware decoding unavailable
* Add system locale settings for Android 13+

### Fixed

* User agent gets enabled when refreshing cookies
* Restrict filenames not working in custom commands

### Changed

* Transition animation should look more smooth now

## [v1.11.1][1.11.1] - 2023-12-16

### Added

* Add `--restrict-filenames` option in yt-dlp
* Add playlist title as an option for subdirectory
* Add more thanks to sponsors

### Fixed

* Fix some minor UI bugs
* Fix an issue causing error when parsing video info

## [v1.11.0][1.11.0] - 2023-11-18

### Added

* Custom output template (`-o` option in yt-dlp)
* Export cookies to a text file
* Make embed metadata in audio files optional
* Add the ability to record download archive, and skip duplicate downloads
* Add cancel button to the download page
* Add input chips for sponsorblock categories
* Add subtitle selection dialog in format page, make auto-translated subtitles available in subtitle
  selection
* Add more thanks to sponsors

### Changed

* Move the directory for storing temporary files to external storage (`Seal/tmp`)
* Change the default output template to `%(title)s.%(ext)s`
* Temporary directory now are enabled by default for downloads in general mode
* Move actions in format page to dropdown menu
* Download subtitles are now available when downloading audio files
* `android:enableOnBackInvokedCallback` is changed to `false` due to compatibility issues

### Fixed

* Fix an issue causes sharing videos to fail on certain devices
* Fix an issue causes uploader marked as null, make uploader_id as a fallback to uploader
* Fix an issue when a user performs multiple clicks causing duplicate navigating behaviors

### Removed

* Custom prefix for output template has been removed, please migrate to custom output template

## [v1.10.0][1.10.0] - 2023-08-30

### Added

**Subtitles**

* Convert subtitles to another format
* Select subtitle language in format selection

**Format selection**

* Display icons(video/audio) on `FormatItem`s
* Split video by chapters
* Select subtitle to download by language names/codes

**Custom commands**

* Create custom command tasks in the Running Tasks page
* Configure download directory separately for custom command tasks
* Select multiple command templates to export & remove

**Cookies**

* Add `CookiesQuickSettingsDialog` for refreshing & configuring cookies in configuration menu
* Add user agent header when downloading with cookies enabled

**Other New Features & UI Improvements**

* Show `PlainToolTip` when long-press on `PlaylistItem`
* Add monochrome theme
* Add proxy configuration for network connections
* Add translations in Swedish and Portuguese

### Fixed

* App crashes when being opened in the system share sheet
* Video not shown in YouTube playlist results
* Cookies cannot be disabled after clearing cookies
* Hide video only formats when save as audio enabled
* Parsing error with decimal value in width/height
* Audio codec preference not works as expected
* Could not fetch video info when `originalUrl` is null

### Changed

**Notable Changes**

* Upgrade target API level to 34 (Android 14)
* Preferred video format changed to two options: Legacy and Quality
* UI improvements to the configuration dialog

**Other Changes**

* Update `ColorScheme`s and components to reflect the new MD3 color roles
* Update youtubedl-android version, added pycryptodomex to the library
* Move Video formats to the bottom of the `FormatPage`
* Notifications now are enabled by default
* Minor UI improvements & changes

## [v1.9.2][1.9.2] - 2023-04-27

### Fixed

* Fix a bug causing Incognito mode not working in v1.9.1
* Fix misplaced quality tags in `AudioQuickSettingsDialog`
* Fix mismatched formats when using Save as audio & Download playlist

## [v1.9.1][1.9.1] - 2023-04-11

### Added

* Add Sponsor page: You can now support this app by sponsoring on GitHub!

### Fixed

* Fix a bug causing warnings not shown in logs of completed custom command tasks
* Fix a bug causing videos not scanned into media library when private mode is enabled

### Changed

* Move the directory for temporary files to `cacheDir`

## [v1.9.0][1.9.0] - 2023-03-12

### Added

* Add Preview channel for auto-updating
* Add an option to update to Nightly builds of yt-dlp
* Add a dialog for F-Droid builds in auto-update settings
* Add a switch for auto-updating yt-dlp
* Add the ability to share files in `VideoDetailDrawer`
* Add a badge to the icon to indicate the count of running processes
* Add a switch for disabling the temporary directory
* Add format & quality preference for audio
* Add custom format sorter
* Add the ability to clip video and audio in `FormatSelectionPage` (experimental)
* Add the ability to edit video titles in `FormatSelectionPage` before downloading
* Add the ability to share the thumbnail url in `FormatSelectionPage`
* Implement a new method to extract cookies from the `WebView` database

### Changed

- Change the operation of open link to long pressing the link button in `VideoDetailDrawer`
- Change the thread number range of multi-threaded download to 1-24
- Change the status bar icon to filled icon
- Change the quick settings for media format in the configuration dialog

### Fixed

- Fix a bug causing high-quality audio not downloaded with YT Premium cookies & YT Music URLs
- UI bug in `ShortcutChip` with long template
- Fix a bug causing empty subtitle language breaks downloads
- Fix an issue causing specific languages not visible in system settings on Android 13+
- Fix a UI bug in the format selection page
- Fix a bug causing app to crash when toasting in Android 5.0
- Fix a UI bug causing LTR texts to display incorrectly in RTL locale environment
- Add legacy app icon for API 21~25

### Known issues

- Cookies may not work as expected in some devices, please try to re-generate cookies after this
  occurs. File an issue on GitHub with your device info when experience errors.

## [v1.8.2][1.8.2] - 2023-02-10

### Fixed

- Trimmed ASCII characters filename
- Unexpected error when downloading multiple video to SD card with quick download
- Error when cropping vertical thumbnails as artwork
- ID conflicts when importing custom templates

### Changed

- Add `horizontalScroll` to `LogPage`
- Revert the URL intent filters

## [v1.8.1][1.8.1] - 2023-02-01

### Fixed

- App crashes when downloading in private mode
- Unexpected ImeActions in TextFields
- Disable SD card download when the directory is not set
- Localized strings for file size texts

## [v1.8.0][1.8.0] - 2023-01-29

### Added

- Download to SD card
- Quick download in parallel
- Task dashboard & log page for custom commands
- Custom shortcuts for command templates
- Subtitle preferences
- Apply `--embed-chapters` for video downloads by default
- New color schemes for UI theming

### Changed

- New transition animation between destinations
- Change `minSdkVersion` to 21 (Android 5.0)
- Accessibility improvements to components
- Revert playlist items limit in v1.7.3
- Scan the download directory to the system media library after running commands
- Change the LongClick operations of `FormatItem` to share the stream URLs

## [v1.7.3][1.7.3] - 2023-01-10

### Fixed

- `Webview` captures Cookies from wrong domains
- Notifications of custom commands remain unfinished status
- App crashes when fails to parse video info for format selection
- App crashes when parsing channel info for playlist download

### Added

- Tips about streams merging in `FormatSelectionPage`

### Changed

- Playlist results are limited to 200 videos

[1.7.3]: https://github.com/JunkFood02/Seal/releases/tag/v1.7.3

[1.8.0]: https://github.com/JunkFood02/Seal/releases/tag/v1.8.0

[1.8.1]: https://github.com/JunkFood02/Seal/releases/tag/v1.8.1

[1.8.2]: https://github.com/JunkFood02/Seal/releases/tag/v1.8.2

[1.9.0]: https://github.com/JunkFood02/Seal/releases/tag/v1.9.0

[1.9.1]: https://github.com/JunkFood02/Seal/releases/tag/v1.9.1

[1.9.2]: https://github.com/JunkFood02/Seal/releases/tag/v1.9.2

[1.10.0]: https://github.com/JunkFood02/Seal/releases/tag/v1.10.0

[1.11.0]: https://github.com/JunkFood02/Seal/releases/tag/v1.11.0

[1.11.1]: https://github.com/JunkFood02/Seal/releases/tag/v1.11.1

[1.11.2]: https://github.com/JunkFood02/Seal/releases/tag/v1.11.2

[1.11.3]: https://github.com/JunkFood02/Seal/releases/tag/v1.11.3

[1.12.0]: https://github.com/JunkFood02/Seal/releases/tag/v1.12.0

[1.12.1]: https://github.com/JunkFood02/Seal/releases/tag/v1.12.1

[1.13.0]: https://github.com/JunkFood02/Seal/releases/tag/v1.13.0

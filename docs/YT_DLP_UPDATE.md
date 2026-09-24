# yt-dlp en SeaRL

Actualización del 24 de septiembre de 2026:

- La dependencia `io.github.junkfood02.youtubedl-android:library:0.17.3` traía `yt-dlp 2024.09.27` en `res/raw/ytdlp`.
- `app/src/main/res/raw/ytdlp` reemplaza ese recurso con el ejecutable Unix oficial de la release estable `2026.08.19` de [yt-dlp](https://github.com/yt-dlp/yt-dlp/releases/tag/2026.08.19).
- SHA-256 del archivo: `1fa6733c37ea6fb51c99ad8fe785e7b7e5f3246c9b980230329d4fb72ed8d4d6`, igual al de `SHA2-256SUMS` de la release.
- El canal predeterminado de las futuras actualizaciones es stable. Quien haya seleccionado nightly explícitamente puede conservarlo desde Ajustes.
- Al iniciar, la app consulta la versión real del ejecutable y la guarda para Ajustes y diagnóstico.
- El APK `generic arm64-v8a debug` generado contiene `res/raw/ytdlp` con el mismo SHA-256 y declara `2026.08.19` en `yt_dlp/version.py`.

La release `2026.08.19` incluye correcciones de extracción para Instagram, TikTok y X. Las versiones recientes de yt-dlp advierten que YouTube puede perder algunos formatos sin un runtime JavaScript compatible. El AAR Android actual incluye Python, FFmpeg y aria2c, pero no un runtime JavaScript. Es necesario verificar video y audio de YouTube en un dispositivo Android real antes de publicar.

Con Python 3.11 se verificó `--version` y extracción simulada, sin descarga, de un post y un reel de Instagram, un video y un Short de YouTube, y audio de YouTube. X devolvió «No video could be found» para dos enlaces antiguos de la suite de yt-dlp; TikTok respondió que bloquea la IP del entorno. Un enlace inválido produjo el error esperado. Threads usa una ruta propia de la app y no se pudo probar desde el CLI de yt-dlp.

Estas pruebas locales no reproducen la integración Android, WebView/cookies, permisos ni la descarga de archivos. La matriz completa del MD y las pruebas en dispositivo siguen pendientes de esa verificación. No había dispositivo conectado por ADB durante esta implementación.

## Videos de Threads

El enlace reportado `https://www.threads.com/share/BAT7JlJ5IW/` devuelve una página genérica por HTTP; su navegación con JavaScript abre `https://www.threads.com/@thecanadiancookie/post/DdrkStqEozs`. yt-dlp `2026.08.19` no acepta el enlace `/share/`. La cola de descargas ahora resuelve Threads en un WebView temporal con las cookies de la app, elige el MP4 del post principal y lo descarga directamente. Las respuestas con video no se confunden con la publicación solicitada. La opción de solo audio convierte el MP4 a M4A mediante FFmpeg y falla explícitamente si la conversión no se logra.

Validación local: el enlace abrió la publicación canónica en un navegador con JavaScript; el selector distinguió el video principal de dos videos en respuestas. Pasaron seis pruebas unitarias de URL, host y resultado del WebView, y `:app:assembleGenericDebug` terminó correctamente. ADB no detectó dispositivos conectados; falta comprobar en Android la carga del WebView temporal, la descarga del MP4, la conversión a audio y su aparición en el historial.

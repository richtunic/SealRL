# Fork de Seal - Descarga Masiva para Instagram y X

## Objetivo

Crear un fork de Seal enfocado en la descarga masiva de contenido desde Instagram y X (Twitter), permitiendo al usuario pegar múltiples enlaces y descargarlos automáticamente mediante una cola de procesamiento.

La aplicación deberá mantener toda la funcionalidad estable de Seal, utilizando yt-dlp como motor principal de descarga, evitando implementar scrapers propios.

---

# Branding

## Nuevo nombre temporal

Opciones sugeridas:

- BulkSeal
- ReelQueue
- QueueDL
- SocialDL
- ClipQueue

## Cambios obligatorios

Eliminar completamente:

- Nombre Seal
- Iconos originales
- Splash original
- Referencias visuales a Seal
- Cualquier asset gráfico propio del proyecto original

Esto es importante porque el proyecto original permite forks, pero no se debe distribuir una app derivada usando el mismo branding de Seal.

---

# Diseño Visual

## Tema obligatorio

La aplicación deberá usar un tema oscuro con fondo negro puro.

## Paleta de colores

### Fondo principal

```css
#000000
```

### Superficies / tarjetas

```css
#0D0D0D
```

### Tarjetas elevadas

```css
#121212
```

### Bordes

```css
#1E1E1E
```

### Texto principal

```css
#FFFFFF
```

### Texto secundario

```css
#B0B0B0
```

### Texto deshabilitado

```css
#666666
```

### Acción principal

```css
#E11D48
```

### Acción principal hover / pressed

```css
#FB7185
```

### Error

```css
#EF4444
```

### Éxito

```css
#22C55E
```

### Advertencia

```css
#F59E0B
```

---

# Estilo General

Inspiración visual:

- Discord Dark
- GitHub Dark
- Vercel Dark
- Linear Dark

Características:

- Fondo negro puro
- Sin gradientes fuertes
- Sombras suaves
- Bordes sutiles
- Diseño minimalista
- Componentes compactos
- Enfoque en velocidad y claridad

---

# Arquitectura General

El fork debe conservar la base técnica de Seal.

La nueva funcionalidad no debe reemplazar el sistema actual de descarga, sino agregar una capa encima.

## Idea principal

```txt
Usuario pega varios enlaces
↓
La app extrae y limpia URLs
↓
Se guardan como tareas en cola
↓
Un worker procesa cada tarea
↓
Cada tarea llama al sistema de descarga existente de Seal / yt-dlp
↓
Se actualiza progreso, estado e historial
```

---

# Funcionalidad Principal

## Descarga Masiva

Agregar nueva sección:

```txt
Descarga Masiva
```

Ubicación sugerida:

```txt
Pantalla Principal
  ├── Descarga Individual
  ├── Descarga Masiva
  └── Historial
```

---

# Interfaz de Descarga Masiva

## Campo principal

Área de texto multilínea:

```txt
Pega uno o más enlaces aquí:

https://www.instagram.com/reel/...
https://x.com/usuario/status/...
https://twitter.com/usuario/status/...
```

## Botones principales

```txt
Agregar a cola
Importar desde portapapeles
Limpiar
```

## Acciones adicionales

```txt
Eliminar duplicados
Validar enlaces
Reintentar fallidos
Cancelar cola
Eliminar completados
```

---

# Procesamiento de URLs

La app debe aceptar enlaces separados por:

- Saltos de línea
- Espacios
- Tabulaciones
- Texto copiado con URLs mezcladas

## Limpieza

Eliminar:

- Enlaces duplicados
- Espacios vacíos
- Caracteres sobrantes al final de la URL
- URLs inválidas

## Normalización

Convertir:

```txt
twitter.com → x.com
mobile.twitter.com → x.com
www.twitter.com → x.com
```

Opcional:

```txt
www.instagram.com → instagram.com
```

---

# Plataformas Soportadas

## Versión inicial

- Instagram Reels
- Instagram Posts con video
- Instagram Stories, si yt-dlp lo permite con cookies
- X videos
- X GIFs
- Twitter videos

## Futuro

- TikTok
- Facebook
- Reddit
- Threads
- YouTube Shorts

Regla: todo debe pasar por yt-dlp.

---

# Regla Principal del Proyecto

No implementar scrapers propios.

Todas las descargas deben seguir utilizando:

```txt
yt-dlp
```

Motivo:

- Menor mantenimiento
- Mayor compatibilidad
- Menos riesgo de romper la app
- Aprovecha actualizaciones constantes de yt-dlp

---

# Sistema de Cola

## Estados

```kotlin
enum class QueueStatus {
    PENDING,
    DOWNLOADING,
    COMPLETED,
    FAILED,
    CANCELED,
    PAUSED
}
```

## Comportamiento esperado

- Las tareas nuevas entran como `PENDING`.
- Solo una tarea debe estar como `DOWNLOADING` en la V1.
- Si termina bien, pasa a `COMPLETED`.
- Si falla, pasa a `FAILED` y guarda el error.
- Si el usuario cancela, pasa a `CANCELED`.
- Si la cola se pausa, las tareas pendientes no deben iniciar.

---

# Modelo de Datos

```kotlin
data class QueueItem(
    val id: Long,
    val url: String,
    val normalizedUrl: String,
    val platform: String,
    val title: String?,
    val progress: Float,
    val status: QueueStatus,
    val errorMessage: String?,
    val outputPath: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val completedAt: Long?
)
```

---

# Persistencia

Utilizar Room Database.

## Tablas sugeridas

```txt
download_queue
download_history
```

## Objetivo

La cola debe mantenerse aunque:

- Se cierre la app
- Android mate el proceso
- El teléfono se reinicie
- Se interrumpa la descarga

---

# DAO sugerido

```kotlin
@Dao
interface DownloadQueueDao {

    @Query("SELECT * FROM download_queue ORDER BY createdAt ASC")
    fun observeQueue(): Flow<List<QueueItemEntity>>

    @Query("SELECT * FROM download_queue WHERE status = :status ORDER BY createdAt ASC LIMIT 1")
    suspend fun getNextByStatus(status: QueueStatus): QueueItemEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<QueueItemEntity>)

    @Update
    suspend fun update(item: QueueItemEntity)

    @Query("DELETE FROM download_queue WHERE status = :status")
    suspend fun deleteByStatus(status: QueueStatus)
}
```

---

# Parser de URLs

```kotlin
object BulkUrlParser {
    private val urlRegex = Regex("""https?://[^\\s]+""")

    fun parse(input: String): List<String> {
        return urlRegex.findAll(input)
            .map { it.value.trim() }
            .map { cleanUrl(it) }
            .map { normalizeUrl(it) }
            .filter { isSupported(it) }
            .distinct()
            .toList()
    }

    private fun cleanUrl(url: String): String {
        return url
            .trim()
            .trimEnd(',', '.', ';', ')', ']')
    }

    private fun normalizeUrl(url: String): String {
        return url
            .replace("https://twitter.com", "https://x.com")
            .replace("https://www.twitter.com", "https://x.com")
            .replace("https://mobile.twitter.com", "https://x.com")
    }

    private fun isSupported(url: String): Boolean {
        return url.contains("instagram.com") ||
               url.contains("x.com") ||
               url.contains("twitter.com")
    }
}
```

---

# Worker de Descarga

Crear:

```txt
BulkDownloadWorker
```

Basado en:

```txt
WorkManager
```

## Responsabilidades

- Buscar la siguiente tarea pendiente
- Marcarla como `DOWNLOADING`
- Ejecutar descarga usando el sistema actual de Seal
- Escuchar progreso si es posible
- Marcar como `COMPLETED` o `FAILED`
- Continuar con la siguiente tarea

---

# Descargas Simultáneas

## V1

```txt
1 descarga simultánea
```

Ventajas:

- Menor consumo de RAM
- Menor consumo de batería
- Menos errores
- Menor riesgo de bloqueos en Instagram/X
- Más estabilidad

## V2

Agregar ajuste:

```txt
Descargas simultáneas:
1
2
3
```

Máximo recomendado:

```txt
3 descargas simultáneas
```

---

# Pantalla de Cola

Cada elemento debe mostrar:

```txt
Miniatura, si está disponible
URL o título
Plataforma
Estado
Progreso
Botón cancelar
Botón reintentar, si falló
```

## Estados visuales

```txt
Pendiente
Descargando
Completado
Fallido
Cancelado
Pausado
```

---

# Historial

Crear pantalla:

```txt
Historial
```

Debe mostrar:

- Descargas completadas
- Descargas fallidas
- Fecha
- Plataforma
- Ruta del archivo
- Error, si existió

Acciones:

```txt
Abrir archivo
Compartir archivo
Reintentar
Eliminar del historial
```

---

# Gestión de Errores

Guardar por cada error:

```txt
URL
Fecha
Plataforma
Mensaje de error
Código de salida, si existe
```

Permitir:

```txt
Reintentar descarga
Reintentar todos los fallidos
Copiar error
```

---

# Soporte para Cookies

Instagram y X pueden requerir sesión/cookies para algunos videos.

Agregar sección en ajustes:

```txt
Cookies / Sesión
```

Opciones:

```txt
Importar cookies.txt
Eliminar cookies
Ver estado de cookies
```

Formatos recomendados:

```txt
Netscape Cookies
cookies.txt
```

Objetivo:

- Mejor compatibilidad con Instagram
- Mejor compatibilidad con X
- Descargar contenido restringido cuando el usuario tenga acceso legítimo

---

# Menú de Cola

Opciones:

```txt
Pausar cola
Continuar cola
Cancelar cola
Eliminar completados
Reintentar fallidos
Eliminar fallidos
Vaciar cola
```

---

# Métricas de Cola

Mostrar resumen superior:

```txt
Total enlaces: 0
Pendientes: 0
Descargando: 0
Completados: 0
Fallidos: 0
```

Opcional:

```txt
Tiempo estimado
Velocidad promedio
Tamaño descargado
```

---

# Notificaciones Android

Agregar notificación persistente mientras la cola esté activa.

Debe mostrar:

```txt
Descargando 3 de 20
Nombre o URL actual
Progreso
```

Acciones:

```txt
Pausar
Cancelar
Abrir app
```

---

# Ajustes Nuevos

Agregar en configuración:

```txt
Descargas masivas
```

Opciones:

```txt
Descargar uno por uno
Número máximo de descargas simultáneas
Reintentar automáticamente fallidos
Número máximo de reintentos
Pausar cola con datos móviles
Solo descargar con WiFi
Importar cookies
```

---

# Seguridad y Buen Uso

La aplicación debe mostrar un aviso discreto:

```txt
Descarga únicamente contenido para el que tengas permiso o derecho de uso.
```

No debe promover:

- Robo de contenido
- Evasión de restricciones
- Redistribución no autorizada
- Bypass de pagos o contenido privado

---

# Roadmap V1

- Fork funcional
- Cambio de nombre y branding
- Tema negro puro
- Pantalla de descarga masiva
- Parser de URLs
- Cola persistente
- Descarga secuencial
- Historial básico
- Reintento de fallidos
- Soporte Instagram
- Soporte X/Twitter

---

# Roadmap V2

- Descargas paralelas configurables
- Importar archivo TXT con enlaces
- Exportar historial
- Filtros por plataforma
- Botón para pegar desde portapapeles
- Limpieza avanzada de URLs
- Mejoras visuales en progreso

---

# Roadmap V3

- Monitor del portapapeles
- Compartir enlace hacia la app
- Descarga automática al detectar URL
- Notificaciones enriquecidas
- Estadísticas de uso
- Programación de descargas
- Modo WiFi-only

---

# Prompt para IA de Programación

Usa este prompt en Codex, Cursor, OpenCode o similar:

```txt
Estoy trabajando en un fork de la app Android Seal, hecha en Kotlin y Jetpack Compose, que utiliza yt-dlp como motor de descarga.

Quiero agregar una función de descarga masiva para Instagram y X/Twitter.

Objetivo:
- Crear una nueva pantalla llamada Descarga Masiva.
- Permitir pegar múltiples enlaces en un TextField multilínea.
- Extraer URLs válidas mediante regex.
- Normalizar twitter.com, mobile.twitter.com y www.twitter.com a x.com.
- Filtrar solo enlaces de instagram.com, x.com y twitter.com.
- Eliminar duplicados.
- Guardar los enlaces en una cola persistente usando Room.
- Procesar la cola con WorkManager.
- Descargar inicialmente uno por uno usando el mismo sistema de descarga actual de Seal/yt-dlp.
- Mostrar estados: pendiente, descargando, completado, fallido, cancelado y pausado.
- Permitir reintentar descargas fallidas.
- Permitir cancelar la cola.
- Agregar historial básico.

También quiero cambiar todo el diseño visual a tema oscuro con fondo negro puro:
- Fondo: #000000
- Tarjetas: #0D0D0D
- Tarjetas elevadas: #121212
- Bordes: #1E1E1E
- Texto principal: #FFFFFF
- Texto secundario: #B0B0B0
- Acción principal: #E11D48
- Hover/pressed: #FB7185
- Error: #EF4444
- Éxito: #22C55E
- Advertencia: #F59E0B

No quiero crear scrapers propios. Todas las descargas deben seguir usando yt-dlp.

Primero analiza la arquitectura actual del proyecto y localiza:
- dónde se ejecutan las descargas
- dónde se define el tema visual
- dónde se maneja el historial
- dónde se podría integrar una pantalla nueva
- cómo se puede reutilizar el flujo actual de descarga para una cola masiva

Después implementa la funcionalidad en pasos pequeños y seguros.
```

---

# Checklist de Implementación

## Fase 1 - Preparación

- [ ] Crear fork del repositorio
- [ ] Clonar fork localmente
- [ ] Compilar app sin cambios
- [ ] Cambiar nombre de app
- [ ] Cambiar package name
- [ ] Cambiar icono
- [ ] Eliminar branding Seal

## Fase 2 - Tema visual

- [ ] Localizar Theme.kt o archivo equivalente
- [ ] Cambiar fondo global a #000000
- [ ] Cambiar superficies a tonos negros
- [ ] Cambiar color primario a #E11D48
- [ ] Revisar botones
- [ ] Revisar cards
- [ ] Revisar dialogs
- [ ] Revisar bottom sheet
- [ ] Revisar snackbar

## Fase 3 - Parser

- [ ] Crear BulkUrlParser
- [ ] Extraer URLs por regex
- [ ] Limpiar URLs
- [ ] Normalizar Twitter/X
- [ ] Filtrar Instagram/X
- [ ] Eliminar duplicados
- [ ] Crear pruebas básicas

## Fase 4 - Base de datos

- [ ] Crear QueueStatus
- [ ] Crear QueueItemEntity
- [ ] Crear DownloadQueueDao
- [ ] Crear DownloadQueueRepository
- [ ] Integrar Room migration si es necesario

## Fase 5 - UI Descarga Masiva

- [ ] Crear BulkDownloadScreen
- [ ] Crear BulkDownloadViewModel
- [ ] Crear TextField multilínea
- [ ] Crear botón Agregar a cola
- [ ] Crear botón Limpiar
- [ ] Crear botón Importar portapapeles
- [ ] Mostrar lista de cola
- [ ] Mostrar métricas

## Fase 6 - Worker

- [ ] Crear BulkDownloadWorker
- [ ] Obtener siguiente pendiente
- [ ] Ejecutar descarga usando flujo existente
- [ ] Actualizar estado
- [ ] Guardar error si falla
- [ ] Continuar con siguiente tarea

## Fase 7 - Historial

- [ ] Mostrar completados
- [ ] Mostrar fallidos
- [ ] Permitir reintentar fallidos
- [ ] Permitir eliminar completados
- [ ] Permitir copiar error

## Fase 8 - Cookies

- [ ] Agregar pantalla de cookies
- [ ] Importar cookies.txt
- [ ] Guardar archivo de cookies
- [ ] Pasar cookies a yt-dlp
- [ ] Permitir eliminar cookies

## Fase 9 - Pruebas

- [ ] Probar Instagram Reel público
- [ ] Probar Instagram Post con video
- [ ] Probar X video público
- [ ] Probar Twitter legacy link
- [ ] Probar duplicados
- [ ] Probar 20 enlaces
- [ ] Probar error de enlace inválido
- [ ] Probar cierre de app durante cola
- [ ] Probar reinicio de app

---

# Resultado Esperado

Una app Android basada en Seal, con identidad propia, tema negro puro y una experiencia especializada para descargar videos de Instagram y X mediante cola masiva.

La V1 debe ser simple, estable y útil:

```txt
Pegar enlaces → Agregar a cola → Descargar uno por uno → Reintentar fallidos
```


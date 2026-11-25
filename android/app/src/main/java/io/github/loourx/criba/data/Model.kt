package io.github.loourx.criba.data

/**
 * Una foto o un vídeo del móvil.
 * [id] es estable entre aperturas de la app: el id de MediaStore, o un hash de la ruta para los
 * archivos que la galería no indexa (enviados y privados de WhatsApp).
 * [mediaUri] solo existe para archivos indexados; sirve para pedir miniaturas al sistema.
 */
data class Item(
    val id: String,
    val path: String,
    val name: String,
    val album: String,
    val isVideo: Boolean,
    val mime: String,
    val size: Long,
    val dateMs: Long,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val whatsapp: Boolean,
    val mediaUri: String?,
)

enum class Choice(val label: String) {
    KEEP("Conservar"),
    DELETE("Borrar"),
    SET_ASIDE("Apartar"),
    SKIP("Saltado"),
}

enum class Mode(val title: String, val hint: String) {
    HEAVY_VIDEOS("Vídeos más pesados", "Lo que más espacio libera por decisión."),
    BIGGEST("Todo, de mayor a menor", "Fotos y vídeos ordenados por tamaño."),
    ALBUM("Un álbum", "Elige uno en la lista."),
    BY_DATE("Todo por fecha", "De lo más antiguo a lo más nuevo."),
    WHATSAPP("WhatsApp: enviados y privados", "Lo que la galería no enseña. De mayor a menor."),
}

/** Una decisión ya aplicada: el archivo se movió de [before] a [now]. */
data class Applied(val before: String, val now: String, val atMs: Long, val size: Long)

data class Decision(val choice: Choice, val applied: Applied? = null)

data class AlbumInfo(val name: String, val count: Int, val bytes: Long, val videos: Int, val pending: Int)

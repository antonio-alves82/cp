package com.classicplayer

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.Collator
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

data class Track(
    val uri: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Int,
    val trackNo: Int
)

data class AlbumInfo(val name: String, val artist: String, val tracks: List<Track>)

data class ArtistInfo(val name: String, val albumCount: Int, val trackCount: Int)

/** Biblioteca de músicas lida da pasta que o usuário escolheu dentro do app. */
object Library {

    const val UNKNOWN_ARTIST = "Artista desconhecido"
    const val UNKNOWN_ALBUM = "Álbum desconhecido"

    private val AUDIO_EXT = setOf(
        "mp3", "m4a", "aac", "flac", "ogg", "oga", "opus", "wav", "3gp", "amr", "mka"
    )

    @Volatile
    var tracks: List<Track> = emptyList()
        private set

    private val collator: Collator =
        Collator.getInstance(Locale("pt", "BR")).apply { strength = Collator.PRIMARY }

    private fun cmp(a: String, b: String): Int = collator.compare(a, b)

    fun setTracks(list: List<Track>) {
        tracks = list
    }

    // ---------------------------------------------------------------- consultas

    fun artists(): List<ArtistInfo> {
        return tracks.groupBy { it.artist }
            .map { (name, list) ->
                ArtistInfo(name, list.map { it.album }.distinct().size, list.size)
            }
            .sortedWith(Comparator { a, b -> cmp(a.name, b.name) })
    }

    fun albums(artist: String? = null): List<AlbumInfo> {
        val src = if (artist == null) tracks else tracks.filter { it.artist == artist }
        return src.groupBy { it.album }
            .map { (name, list) ->
                val artists = list.map { it.artist }.distinct()
                AlbumInfo(
                    name,
                    if (artists.size == 1) artists[0] else "Vários artistas",
                    sortAlbumTracks(list)
                )
            }
            .sortedWith(Comparator { a, b -> cmp(a.name, b.name) })
    }

    private fun sortAlbumTracks(list: List<Track>): List<Track> {
        return list.sortedWith(Comparator { a, b ->
            val na = if (a.trackNo == 0) Int.MAX_VALUE else a.trackNo
            val nb = if (b.trackNo == 0) Int.MAX_VALUE else b.trackNo
            if (na != nb) na.compareTo(nb) else cmp(a.title, b.title)
        })
    }

    // ---------------------------------------------------------------- varredura

    private class FileRef(val docUri: Uri, val name: String)

    private fun isAudio(name: String, mime: String): Boolean {
        if (mime.startsWith("audio/") || mime == "application/ogg") return true
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in AUDIO_EXT
    }

    private fun collect(ctx: Context, tree: Uri, parentId: String, out: MutableList<FileRef>) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val projection = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE
        )
        ctx.contentResolver.query(children, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: ""
                val mime = c.getString(2) ?: ""
                if (mime == Document.MIME_TYPE_DIR) {
                    collect(ctx, tree, id, out)
                } else if (isAudio(name, mime)) {
                    out.add(FileRef(DocumentsContract.buildDocumentUriUsingTree(tree, id), name))
                }
            }
        }
    }

    private fun readTrack(ctx: Context, ref: FileRef): Track {
        val fallbackTitle = ref.name.substringBeforeLast('.')
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, ref.docUri)
            fun meta(key: Int): String? =
                r.extractMetadata(key)?.trim()?.takeIf { it.isNotEmpty() }

            val title = meta(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: fallbackTitle
            val artist = meta(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?: meta(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
                ?: UNKNOWN_ARTIST
            val album = meta(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: UNKNOWN_ALBUM
            val duration = meta(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val trackNo = meta(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
                ?.substringBefore('/')?.trim()?.toIntOrNull() ?: 0
            return Track(ref.docUri.toString(), title, artist, album, duration.toInt(), trackNo)
        } catch (e: Exception) {
            return Track(ref.docUri.toString(), fallbackTitle, UNKNOWN_ARTIST, UNKNOWN_ALBUM, 0, 0)
        } finally {
            try {
                r.release()
            } catch (e: Exception) {
                // ignora
            }
        }
    }

    /** Varre a pasta (recursivamente) e devolve as faixas ordenadas por título. Rodar em thread de fundo. */
    fun scan(ctx: Context, tree: Uri, onProgress: (Int) -> Unit): List<Track> {
        val appCtx = ctx.applicationContext
        val files = ArrayList<FileRef>()
        collect(appCtx, tree, DocumentsContract.getTreeDocumentId(tree), files)

        val pool = Executors.newFixedThreadPool(4)
        val done = AtomicInteger(0)
        try {
            val futures = files.map { f ->
                pool.submit(Callable<Track> {
                    val t = readTrack(appCtx, f)
                    onProgress(done.incrementAndGet())
                    t
                })
            }
            val result = futures.map { it.get() }
            return result.sortedWith(Comparator { a, b -> cmp(a.title, b.title) })
        } finally {
            pool.shutdown()
        }
    }

    // ---------------------------------------------------------------- cache

    private fun cacheFile(ctx: Context) = File(ctx.filesDir, "library.json")

    fun save(ctx: Context) {
        try {
            val arr = JSONArray()
            for (t in tracks) {
                arr.put(
                    JSONObject()
                        .put("u", t.uri)
                        .put("t", t.title)
                        .put("a", t.artist)
                        .put("b", t.album)
                        .put("d", t.durationMs)
                        .put("n", t.trackNo)
                )
            }
            cacheFile(ctx).writeText(arr.toString())
        } catch (e: Exception) {
            // cache é opcional
        }
    }

    fun load(ctx: Context) {
        try {
            val f = cacheFile(ctx)
            if (!f.exists()) return
            val arr = JSONArray(f.readText())
            val list = ArrayList<Track>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    Track(
                        o.getString("u"),
                        o.getString("t"),
                        o.getString("a"),
                        o.getString("b"),
                        o.optInt("d", 0),
                        o.optInt("n", 0)
                    )
                )
            }
            tracks = list
        } catch (e: Exception) {
            // cache corrompido: ignora e deixa o usuário atualizar a biblioteca
        }
    }
}

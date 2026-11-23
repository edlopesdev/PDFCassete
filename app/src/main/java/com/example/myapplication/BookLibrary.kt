package com.example.myapplication

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlin.math.min
import kotlin.math.roundToInt

/** Metadados, preferências e posição de reprodução persistidos para um PDF. */
data class Book(
    val id: String,
    val fileName: String,
    val title: String,
    val author: String,
    val pageCount: Int = 0,
    val sizeBytes: Long,
    val importedAt: Long,
    val lastPlayedAt: Long = 0,
    val playedTimeMillis: Long = 0,
    val currentPage: Int = 0,
    val currentParagraph: Int = 0,
    val completed: Boolean = false,
    val speechRate: Float = 1f,
    val voiceName: String = "",
    val status: String = STATUS_PROCESSING,
    val error: String = ""
)

// Estados possíveis do ciclo de vida de um documento na biblioteca.
const val STATUS_PROCESSING = "processing"
const val STATUS_READY = "ready"
const val STATUS_NO_TEXT = "no_text"
const val STATUS_ERROR = "error"
const val STATUS_PENDING = "pending"

/**
 * Importa e processa PDFs, guarda os arquivos no espaço privado do app e persiste os metadados.
 * O texto extraído é armazenado em arquivos por página para carregamento sob demanda.
 */
class BookLibrary(context: Context) {
    private val appContext = context.applicationContext
    private val root = File(appContext.filesDir, "cassette_books")
    private val preferences =
        appContext.getSharedPreferences("cassette_library", Context.MODE_PRIVATE)

    init {
        // Inicializa os recursos do PDFBox e prepara a pasta local da biblioteca.
        PDFBoxResourceLoader.init(appContext)
        if (!root.exists()) root.mkdirs()
    }

    /** Lê os livros guardados nas preferências e reconstrói seus respectivos modelos. */
    fun all(): List<Book> = decodeBooks(preferences.getString(KEY_BOOKS, "[]") ?: "[]")

    /** Copia e valida um PDF selecionado pelo provedor de documentos do Android. */
    fun importPdf(uri: Uri): Book {
        val id = UUID.randomUUID().toString()
        val bookDir = File(root, id)
        if (!bookDir.mkdirs()) throw IOException("Não foi possível preparar o armazenamento do PDF.")
        val pdfFile = File(bookDir, "source.pdf")
        try {
            val input = appContext.contentResolver.openInputStream(uri)
                ?: throw IOException("Não foi possível abrir o arquivo selecionado.")
            var size = 0L
            input.use { source ->
                FileOutputStream(pdfFile).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        size += read
                        if (size > MAX_FILE_BYTES) {
                            throw IOException("O PDF excede o limite de 100 MB.")
                        }
                        output.write(buffer, 0, read)
                    }
                }
            }
            if (size < PDF_HEADER.size || pdfFile.inputStream().use { input ->
                    val header = ByteArray(PDF_HEADER.size)
                    input.read(header) == header.size && header.contentEquals(PDF_HEADER)
                }.not()
            ) {
                throw IOException("O arquivo selecionado não contém um cabeçalho PDF válido.")
            }

            val fallbackName = queryFileName(uri)
            val book = Book(
                id = id,
                fileName = fallbackName,
                title = fallbackName.removeSuffix(".pdf").ifBlank { "Documento sem título" },
                author = "",
                sizeBytes = size,
                importedAt = System.currentTimeMillis()
            )
            save(book)
            return book
        } catch (error: Exception) {
            bookDir.deleteRecursively()
            throw error
        }
    }

    /** Obtém um nome legível do provedor, recorrendo à URI quando não há metadados. */
    private fun queryFileName(uri: Uri): String {
        var name: String? = null
        try {
            appContext.contentResolver.query(
                uri,
                arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) {
                        name = cursor.getString(index)
                    }
                }
            }
        } catch (_: Exception) {}

        if (name.isNullOrBlank()) {
            val segment = uri.lastPathSegment?.substringAfterLast('/')
            if (!segment.isNullOrBlank() && !segment.startsWith("document:")) {
                name = segment
            } else {
                val path = uri.path?.substringAfterLast('/')
                if (!path.isNullOrBlank() && !path.startsWith("document:")) {
                    name = path
                }
            }
        }

        if (name.isNullOrBlank() || name.startsWith("document:")) {
            name = "Documento.pdf"
        }

        return name
    }

    /**
     * Extrai metadados e texto página a página, determina se há texto narrável e gera a capa.
     * Esta operação deve ser chamada fora da thread principal.
     */
    fun process(book: Book): Book {
        val source = File(File(root, book.id), "source.pdf")
        val textDir = File(root, book.id)
        val result = PDDocument.load(source).use { document ->
            val metadata = document.documentInformation
            val title = metadata.title?.trim()?.takeIf(String::isNotEmpty) ?: book.title
            val author = metadata.author?.trim().orEmpty()
            val pages = document.numberOfPages
            for (page in 1..pages) {
                val stripper = PDFTextStripper().apply {
                    startPage = page
                    endPage = page
                    sortByPosition = true
                }
                val pageText = stripper.getText(document)
                    .replace("\u0000", "")
                    .trim()
                File(textDir, "page-$page.txt").writeText(pageText, StandardCharsets.UTF_8)
            }
            val hasText = (1..pages).any { pageText(book.id, it).isNotBlank() }
            book.copy(
                title = title,
                author = author,
                pageCount = pages,
                status = if (hasText) STATUS_READY else STATUS_NO_TEXT,
                error = ""
            )
        }
        renderCover(book.id)
        save(result)
        return result
    }

    /** Retorna o arquivo da capa, gerando-o se ainda não existir. */
    fun coverFile(bookId: String): File {
        val cover = File(File(root, bookId), COVER_FILE_NAME)
        if (!cover.isFile) renderCover(bookId)
        return cover
    }

    /** Renderiza a primeira página do PDF para uma imagem usada na lista da biblioteca. */
    private fun renderCover(bookId: String) {
        val bookDir = File(root, bookId)
        val source = File(bookDir, "source.pdf")
        if (!source.isFile) throw IOException("O PDF original não foi encontrado.")
        val temporary = File(bookDir, "$COVER_FILE_NAME.tmp")
        val descriptor = ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY)
        try {
            val renderer = PdfRenderer(descriptor)
            try {
                if (renderer.pageCount == 0) throw IOException("O PDF não contém páginas.")
                val page = renderer.openPage(0)
                try {
                    val scale = min(640f / page.width, 900f / page.height)
                    val bitmap = Bitmap.createBitmap(
                        (page.width * scale).roundToInt().coerceAtLeast(1),
                        (page.height * scale).roundToInt().coerceAtLeast(1),
                        Bitmap.Config.ARGB_8888
                    )
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    FileOutputStream(temporary).use { output ->
                        if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                            throw IOException("Não foi possível salvar a capa do PDF.")
                        }
                    }
                    bitmap.recycle()
                } finally {
                    page.close()
                }
            } finally {
                renderer.close()
            }
        } catch (error: Exception) {
            temporary.delete()
            throw error
        } finally {
            descriptor.close()
        }
        val cover = File(bookDir, COVER_FILE_NAME)
        if (!temporary.renameTo(cover)) {
            temporary.delete()
            throw IOException("Não foi possível preparar a capa do PDF.")
        }
    }

    /** Lê o texto previamente extraído; o argumento da página começa em 1. */
    fun pageText(bookId: String, page: Int): String {
        if (page < 1) return ""
        val file = File(File(root, bookId), "page-$page.txt")
        return if (file.isFile) file.readText(StandardCharsets.UTF_8) else ""
    }

    /** Atualiza ou insere o registo do livro nas preferências locais. */
    fun update(book: Book) = save(book)

    /** Remove os arquivos do livro e seu registro da biblioteca. */
    fun delete(book: Book) {
        File(root, book.id).deleteRecursively()
        persist(all().filterNot { it.id == book.id })
    }

    /** Substitui o registo do mesmo ID e grava a lista atualizada. */
    private fun save(book: Book) {
        persist(all().filterNot { it.id == book.id } + book)
    }

    /** Serializa todos os campos dos livros em JSON nas SharedPreferences. */
    private fun persist(books: List<Book>) {
        val json = JSONArray()
        books.forEach { book ->
            json.put(
                JSONObject().apply {
                    put("id", book.id)
                    put("fileName", book.fileName)
                    put("title", book.title)
                    put("author", book.author)
                    put("pageCount", book.pageCount)
                    put("sizeBytes", book.sizeBytes)
                    put("importedAt", book.importedAt)
                    put("lastPlayedAt", book.lastPlayedAt)
                    put("playedTimeMillis", book.playedTimeMillis)
                    put("currentPage", book.currentPage)
                    put("currentParagraph", book.currentParagraph)
                    put("completed", book.completed)
                    put("speechRate", book.speechRate.toDouble())
                    put("voiceName", book.voiceName)
                    put("status", book.status)
                    put("error", book.error)
                }
            )
        }
        preferences.edit().putString(KEY_BOOKS, json.toString()).apply()
    }

    /** Desserializa os registos e corrige nomes vazios/antigos com valores utilizáveis. */
    private fun decodeBooks(encoded: String): List<Book> {
        val json = JSONArray(encoded)
        return (0 until json.length()).map { index ->
            val item = json.getJSONObject(index)
            var fileName = item.optString("fileName")
            var title = item.optString("title")
            if (fileName.isBlank() || fileName.startsWith("document:")) {
                fileName = "Documento.pdf"
            }
            if (title.isBlank() || title.startsWith("document:")) {
                title = fileName.removeSuffix(".pdf").ifBlank { "Documento sem título" }
            }
            Book(
                id = item.getString("id"),
                fileName = fileName,
                title = title,
                author = item.optString("author"),
                pageCount = item.optInt("pageCount"),
                sizeBytes = item.optLong("sizeBytes"),
                importedAt = item.optLong("importedAt"),
                lastPlayedAt = item.optLong("lastPlayedAt"),
                playedTimeMillis = item.optLong("playedTimeMillis"),
                currentPage = item.optInt("currentPage"),
                currentParagraph = item.optInt("currentParagraph"),
                completed = item.optBoolean("completed"),
                speechRate = item.optDouble("speechRate", 1.0).toFloat(),
                voiceName = item.optString("voiceName"),
                status = item.optString("status", STATUS_PENDING),
                error = item.optString("error")
            )
        }
    }

    companion object {
        // Limite de importação e nomes/chaves locais utilizados pelo armazenamento.
        const val MAX_FILE_BYTES = 100L * 1024 * 1024
        private const val KEY_BOOKS = "books"
        private const val COVER_FILE_NAME = "cover.png"
        private val PDF_HEADER = "%PDF-".toByteArray(StandardCharsets.US_ASCII)
    }
}

package com.example.myapplication

import android.net.Uri
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.myapplication.ui.theme.MyApplicationTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private const val KEY_LAST_BOOK_ID = "last_book_id"

/** Texto de uma página já dividido em trechos e pronto para ser usado pelo player. */
private data class PreparedPage(
    val bookId: String,
    val page: Int,
    val paragraphs: List<String>
)

/** Cores configuráveis da interface, convertidas para o tema do Compose. */
private data class AppColors(
    val accent: Color,
    val background: Color,
    val text: Color
)

/**
 * Activity que conecta o ciclo de vida do Android, o mecanismo TTS e a interface Compose.
 * A lógica de telas e reprodução fica em [CassetteReadApp].
 */
class MainActivity : ComponentActivity() {
    private lateinit var speech: TextToSpeech
    private var onSpeechDone: ((String?) -> Unit)? = null
    private var onSpeechError: ((String?) -> Unit)? = null
    private var persistProgress: (() -> Unit)? = null
    private var onPlaybackCommand: ((String) -> Unit)? = null
    // Recebe os comandos enviados pelos botões da notificação e os encaminha à interface.
    private val playbackCommandReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: Intent) {
            intent.getStringExtra(AudioPlaybackService.EXTRA_COMMAND)?.let { command ->
                onPlaybackCommand?.invoke(command)
            }
        }
    }
    private var playbackCommandReceiverRegistered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // O broadcast não é exportado: somente componentes deste aplicativo podem controlá-lo.
        ContextCompat.registerReceiver(
            this,
            playbackCommandReceiver,
            IntentFilter(AudioPlaybackService.ACTION_PLAYBACK_COMMAND),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        playbackCommandReceiverRegistered = true

        // O TTS é assíncrono; esses estados atualizam a UI quando o mecanismo estiver pronto.
        var ready by mutableStateOf(false)
        var voices by mutableStateOf(emptyList<Voice>())
        speech = TextToSpeech(this) { result ->
            if (result == TextToSpeech.SUCCESS) {
                ready = true
                speech.setLanguage(Locale.forLanguageTag("pt-BR"))
                voices = speech.voices.orEmpty()
                    .filter { it.locale != null }
                    .sortedWith(compareBy<Voice>({ it.locale.displayName }, { it.name }))
            }

        }
        // O TTS chama os callbacks fora da UI; runOnUiThread permite atualizar o estado Compose.
        speech.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) {
                runOnUiThread { onSpeechDone?.invoke(utteranceId) }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                runOnUiThread { onSpeechError?.invoke(utteranceId) }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                runOnUiThread { onSpeechError?.invoke(utteranceId) }
            }
        })

        // As preferências guardam personalização visual independente dos dados dos livros.
        val settings = getSharedPreferences("cassette_settings", MODE_PRIVATE)
        setContent {
            // Converte as cores persistidas em estado Compose para aplicar mudanças imediatamente.
            var accentArgb by remember {
                mutableIntStateOf(settings.getInt(KEY_ACCENT_COLOR, DEFAULT_ACCENT_COLOR))
            }
            var backgroundArgb by remember {
                mutableIntStateOf(settings.getInt(KEY_BACKGROUND_COLOR, DEFAULT_BACKGROUND_COLOR))
            }
            var textArgb by remember {
                mutableIntStateOf(settings.getInt(KEY_TEXT_COLOR, DEFAULT_TEXT_COLOR))
            }
            val appColors = AppColors(
                accent = Color(accentArgb),
                background = Color(backgroundArgb),
                text = Color(textArgb)
            )
            MyApplicationTheme(
                darkTheme = true,
                accentColor = appColors.accent,
                backgroundColor = appColors.background,
                textColor = appColors.text
            ) {
                CassetteReadApp(
                    library = remember { BookLibrary(this) },
                    appColors = appColors,
                    onColorsChanged = { colors ->
                        accentArgb = colors.accent.toArgb()
                        backgroundArgb = colors.background.toArgb()
                        textArgb = colors.text.toArgb()
                        settings.edit()
                            .putInt(KEY_ACCENT_COLOR, accentArgb)
                            .putInt(KEY_BACKGROUND_COLOR, backgroundArgb)
                            .putInt(KEY_TEXT_COLOR, textArgb)
                            .apply()
                    },
                    speech = speech,
                    ttsReady = ready,
                    voices = voices,
                    setDoneListener = { onSpeechDone = it },
                    setErrorListener = { onSpeechError = it },
                    setPersistListener = { persistProgress = it },
                    startPlaybackService = { title -> AudioPlaybackService.start(this, title) },
                    updatePlaybackService = { title, playing ->
                        AudioPlaybackService.update(this, title, playing)
                    },
                    stopPlaybackService = { AudioPlaybackService.stop(this) },
                    setPlaybackCommandListener = { onPlaybackCommand = it }
                )
            }
        }
    }

    override fun onPause() {
        // Salva a posição atual antes de a Activity deixar de estar visível.
        persistProgress?.invoke()
        super.onPause()
    }

    override fun onDestroy() {
        // Desconecta callbacks e recursos do sistema para evitar referências após a Activity.
        onSpeechDone = null
        onSpeechError = null
        persistProgress = null
        onPlaybackCommand = null
        if (playbackCommandReceiverRegistered) {
            unregisterReceiver(playbackCommandReceiver)
            playbackCommandReceiverRegistered = false
        }
        AudioPlaybackService.stop(this)
        if (::speech.isInitialized) {
            speech.stop()
            speech.shutdown()
        }
        super.onDestroy()
    }

    companion object {
        // Chaves e paleta inicial usadas para persistir as cores escolhidas.
        private const val KEY_ACCENT_COLOR = "accent_color"
        private const val KEY_BACKGROUND_COLOR = "background_color"
        private const val KEY_TEXT_COLOR = "text_color"
        private val DEFAULT_ACCENT_COLOR = Color(0xFFFF9A56).toArgb()
        private val DEFAULT_BACKGROUND_COLOR = Color(0xFF101111).toArgb()
        private val DEFAULT_TEXT_COLOR = Color(0xFFF3F0EA).toArgb()
    }
}

@Composable
private fun CassetteReadApp(
    library: BookLibrary,
    appColors: AppColors,
    onColorsChanged: (AppColors) -> Unit,
    speech: TextToSpeech,
    ttsReady: Boolean,
    voices: List<Voice>,
    setDoneListener: ((String?) -> Unit) -> Unit,
    setErrorListener: ((String?) -> Unit) -> Unit,
    setPersistListener: (() -> Unit) -> Unit,
    startPlaybackService: (String) -> Unit,
    updatePlaybackService: (String, Boolean) -> Unit,
    stopPlaybackService: () -> Unit,
    setPlaybackCommandListener: ((String) -> Unit) -> Unit
) {
    // Reúne estado da tela, ações de reprodução e navegação entre player, biblioteca e ajustes.
    val context = LocalContext.current
    val preferences = remember(context) {
        context.getSharedPreferences("cassette_settings", Context.MODE_PRIVATE)
    }
    // Estado de domínio e navegação: a seleção inicial prioriza o último livro ouvido.
    var books by remember { mutableStateOf(library.all()) }
    var selectedId by remember {
        val savedId = preferences.getString(KEY_LAST_BOOK_ID, null)
        mutableStateOf(
            books.firstOrNull { it.id == savedId }?.id
                ?: books.maxByOrNull { it.lastPlayedAt }
                    ?.takeIf { it.lastPlayedAt > 0L }
                    ?.id
                ?: books.firstOrNull()?.id
        )
    }
    val selectedBook = books.firstOrNull { it.id == selectedId }
    var showLibrary by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showLog by remember { mutableStateOf(false) }
    val logContent = remember(context) {
        context.assets.open("log.txt").bufferedReader().use { it.readText() }
    }
    var hasUnreadLog by remember { mutableStateOf(true) }
    // Estado do player e preferências temporárias da sessão de leitura.
    var isPlaying by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var playbackServiceStarted by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var currentPage by remember(selectedId) {
        mutableIntStateOf(selectedBook?.currentPage?.coerceAtLeast(0) ?: 0)
    }
    var currentParagraph by remember(selectedId) {
        mutableIntStateOf(selectedBook?.currentParagraph?.coerceAtLeast(0) ?: 0)
    }
    var paragraphs by remember(selectedId, currentPage) { mutableStateOf(emptyList<String>()) }
    var prefetchedPage by remember { mutableStateOf<PreparedPage?>(null) }
    var activeUtteranceId by remember { mutableStateOf<String?>(null) }
    var rate by remember(selectedId) {
        mutableFloatStateOf(selectedBook?.speechRate ?: 1f)
    }
    var volume by remember { mutableFloatStateOf(1f) }
    var sleepMinutes by remember { mutableIntStateOf(0) }
    var sleepDeadline by remember { mutableStateOf<Long?>(null) }
    var sleepAtDocumentEnd by remember { mutableStateOf(false) }
    var showSleepOptions by remember { mutableStateOf(false) }
    var showVoiceOptions by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    var sortByName by remember { mutableStateOf(false) }
    var renameBook by remember { mutableStateOf<Book?>(null) }
    var renameValue by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    // Atualiza o registro opcional e recarrega a lista persistida da biblioteca.
    fun refresh(updated: Book? = null) {
        if (updated != null) library.update(updated)
        books = library.all()
    }

    fun rememberBook(bookId: String?) {
        preferences.edit().apply {
            if (bookId == null) remove(KEY_LAST_BOOK_ID)
            else putString(KEY_LAST_BOOK_ID, bookId)
        }.apply()
    }

    // Persiste posição e velocidade para retomar o documento posteriormente.
    fun saveProgress(page: Int = currentPage, paragraph: Int = currentParagraph) {
        val book = books.firstOrNull { it.id == selectedId } ?: return
        val updated = book.copy(
            currentPage = page.coerceAtLeast(0),
            currentParagraph = paragraph.coerceAtLeast(0),
            lastPlayedAt = System.currentTimeMillis(),
            speechRate = rate,
            completed = false
        )
        refresh(updated)
    }

    // Encerra por completo o serviço; usado quando a sessão de reprodução termina.
    fun stopForegroundPlayback() {
        stopPlaybackService()
        playbackServiceStarted = false
    }

    fun pauseForegroundPlayback() {
        if (playbackServiceStarted) {
            updatePlaybackService(selectedBook?.title ?: "", false)
        }
    }

    // Para o TTS e sincroniza o estado do player/serviço, opcionalmente voltando ao início.
    fun stopPlayback(reset: Boolean = false) {
        isPlaying = false
        activeUtteranceId = null
        speech.stop()
        if (reset) stopForegroundPlayback() else pauseForegroundPlayback()
        if (reset) {
            currentPage = 0
            currentParagraph = 0
            saveProgress(0, 0)
        } else {
            saveProgress()
        }
    }

    // Configura TTS e serviço de mídia, registra a posição e inicia um trecho identificado.
    fun speakText(text: String, page: Int, paragraph: Int) {
        val book = books.firstOrNull { it.id == selectedId } ?: return
        rememberBook(book.id)
        currentPage = page
        currentParagraph = paragraph
        isPlaying = true
        if (!playbackServiceStarted) {
            startPlaybackService(book.title)
            playbackServiceStarted = true
        }
        updatePlaybackService(book.title, true)
        val voice = voices.firstOrNull { it.name == book.voiceName }
        if (voice != null) speech.voice = voice
        speech.setSpeechRate(rate)
        val volumeParameters = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume)
        }
        saveProgress(page, paragraph)
        val utteranceId = "$selectedId:$page:$paragraph:${System.nanoTime()}"
        activeUtteranceId = utteranceId
        val result = speech.speak(
            text,
            TextToSpeech.QUEUE_FLUSH,
            volumeParameters,
            utteranceId
        )
        if (result == TextToSpeech.ERROR) {
            isPlaying = false
            stopForegroundPlayback()
            message = "Não foi possível iniciar a narração. Verifique o mecanismo de voz do dispositivo."
        }
    }

    // Carrega texto em background, ignora páginas sem texto e opcionalmente inicia a fala.
    fun loadAndSpeak(page: Int, paragraph: Int, shouldSpeak: Boolean) {
        val book = books.firstOrNull { it.id == selectedId } ?: return
        scope.launch {
            isLoading = true
            try {
                var foundPage = page.coerceAtLeast(0)
                val prepared = prefetchedPage
                var foundParagraphs =
                    if (prepared?.bookId == book.id && prepared.page == foundPage) {
                        prefetchedPage = null
                        prepared.paragraphs
                    } else {
                        emptyList()
                    }
                if (foundParagraphs.isEmpty()) {
                    while (foundPage < book.pageCount && foundParagraphs.isEmpty()) {
                        foundParagraphs = withContext(Dispatchers.IO) {
                            splitForSpeech(library.pageText(book.id, foundPage + 1))
                        }
                        if (foundParagraphs.isEmpty()) foundPage++
                    }
                }
                if (foundParagraphs.isEmpty()) {
                    isPlaying = false
                    stopForegroundPlayback()
                    isLoading = false
                    activeUtteranceId = null
                    sleepAtDocumentEnd = false
                    sleepMinutes = 0
                    sleepDeadline = null
                    val completed = book.copy(
                        currentPage = (book.pageCount - 1).coerceAtLeast(0),
                        currentParagraph = 0,
                        completed = true,
                        lastPlayedAt = System.currentTimeMillis()
                    )
                    refresh(completed)
                    message = "Documento concluído."
                    return@launch
                }
                currentPage = foundPage
                paragraphs = foundParagraphs
                val foundParagraph = paragraph.coerceIn(0, foundParagraphs.lastIndex)
                currentParagraph = foundParagraph
                if (shouldSpeak) {
                    speakText(foundParagraphs[foundParagraph], foundPage, foundParagraph)
                } else {
                    isPlaying = false
                    stopForegroundPlayback()
                    val updated = book.copy(
                        currentPage = foundPage,
                        currentParagraph = foundParagraph,
                        completed = false
                    )
                    refresh(updated)
                }
            } catch (error: Exception) {
                isPlaying = false
                stopForegroundPlayback()
                message = "Erro ao preparar o texto: ${error.message ?: "falha desconhecida"}"
            } finally {
                isLoading = false
            }
        }
    }

    // Valida se o livro e o TTS estão prontos antes de começar ou retomar a leitura.
    fun playOrResume() {
        val book = selectedBook ?: return
        if (!ttsReady) {
            message = "O mecanismo de voz ainda está iniciando."
            return
        }
        if (book.status != STATUS_READY) {
            message = if (book.status == STATUS_NO_TEXT) {
                "Este PDF parece digitalizado. OCR não está disponível nesta versão."
            } else {
                "Este documento ainda não está pronto para reprodução."
            }
            return
        }
        if (book.completed) {
            currentPage = 0
            currentParagraph = 0
            loadAndSpeak(0, 0, true)
        } else if (paragraphs.isNotEmpty()) {
            speakText(
                paragraphs.getOrElse(currentParagraph) { paragraphs.first() },
                currentPage,
                currentParagraph
            )
        } else {
            loadAndSpeak(book.currentPage, book.currentParagraph, true)
        }
    }

    // Muda a posição da leitura e preserva se a navegação deve continuar falando.
    fun navigate(page: Int, paragraph: Int) {
        val resumeAfterNavigation = isPlaying
        isPlaying = false
        activeUtteranceId = null
        speech.stop()
        if (!resumeAfterNavigation) stopForegroundPlayback()
        loadAndSpeak(page, paragraph, resumeAfterNavigation)
    }

    // Ao terminar um trecho, fala o próximo ou carrega a página seguinte.
    setDoneListener { utteranceId ->
        if (isPlaying && utteranceId == activeUtteranceId) {
            activeUtteranceId = null
            val nextParagraph = currentParagraph + 1
            if (nextParagraph < paragraphs.size) {
                speakText(paragraphs[nextParagraph], currentPage, nextParagraph)
            } else {
                loadAndSpeak(currentPage + 1, 0, true)
            }
        }
    }
    // Em falha de síntese, pausa o serviço, guarda o progresso e informa a pessoa usuária.
    setErrorListener { utteranceId ->
        if (utteranceId == activeUtteranceId) {
            activeUtteranceId = null
            isPlaying = false
            stopForegroundPlayback()
            message = "A narração foi interrompida. Verifique se há uma voz disponível no dispositivo."
            saveProgress()
        }
    }
    setPersistListener { saveProgress() }
    LaunchedEffect(logContent) {
        hasUnreadLog = true
    }
    setPlaybackCommandListener { command ->
        when (command) {
            AudioPlaybackService.ACTION_PLAY -> playOrResume()
            AudioPlaybackService.ACTION_PAUSE -> stopPlayback()
            AudioPlaybackService.ACTION_NEXT -> {
                if (paragraphs.isNotEmpty() && currentParagraph < paragraphs.lastIndex) {
                    navigate(currentPage, currentParagraph + 1)
                } else {
                    navigate(currentPage + 1, 0)
                }
            }
            AudioPlaybackService.ACTION_PREVIOUS -> {
                if (currentParagraph > 0) {
                    navigate(currentPage, currentParagraph - 1)
                } else if (currentPage > 0) {
                    navigate(currentPage - 1, Int.MAX_VALUE)
                }
            }
        }
    }

    // Carrega o texto da página selecionada fora da thread principal.
    LaunchedEffect(selectedId, currentPage) {
        val book = books.firstOrNull { it.id == selectedId }
        paragraphs = if (book?.status == STATUS_READY) {
            withContext(Dispatchers.IO) {
                splitForSpeech(library.pageText(book.id, currentPage + 1))
            }
        } else {
            emptyList()
        }
    }

    // Prepara antecipadamente a próxima página que contenha texto, se houver.
    LaunchedEffect(selectedId, currentPage, selectedBook?.status, selectedBook?.pageCount) {
        val book = selectedBook
        prefetchedPage = null
        if (book?.status == STATUS_READY) {
            val current = currentPage
            var pageToPrepare = current + 1
            while (pageToPrepare < book.pageCount) {
                val preparedParagraphs = withContext(Dispatchers.IO) {
                    splitForSpeech(library.pageText(book.id, pageToPrepare + 1))
                }
                if (preparedParagraphs.isNotEmpty()) {
                    if (selectedId == book.id && currentPage == current) {
                        prefetchedPage = PreparedPage(book.id, pageToPrepare, preparedParagraphs)
                    }
                    break
                }
                pageToPrepare++
            }
        }
    }

    // Atualiza periodicamente o progresso durante a narração.
    LaunchedEffect(isPlaying, selectedId) {
        while (isPlaying) {
            delay(15_000)
            if (isPlaying) saveProgress()
        }
    }

    // O temporizador pausa a reprodução quando o prazo definido é atingido.
    LaunchedEffect(sleepDeadline, isPlaying) {
        val deadline = sleepDeadline
        if (deadline != null && isPlaying) {
            delay((deadline - System.currentTimeMillis()).coerceAtLeast(0L))
            if (isPlaying) {
                stopPlayback()
                sleepMinutes = 0
                sleepDeadline = null
                message = "Temporizador concluído. Progresso salvo."
            }
        }
    }

    // Se o app foi interrompido durante o processamento, permite processar o livro de novo.
    LaunchedEffect(Unit) {
        val interrupted = books.filter { it.status == STATUS_PROCESSING }
        if (interrupted.isNotEmpty()) {
            books = books.map {
                if (it.status == STATUS_PROCESSING) it.copy(status = STATUS_PENDING) else it
            }
            books.forEach { book ->
                if (book.status == STATUS_PROCESSING) {
                    val updated = book.copy(status = STATUS_PENDING)
                    library.update(updated)
                }
            }
        }
    }

    // Abre o seletor de documentos para importar vários PDFs escolhidos pela pessoa usuária.
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                uris.forEach { uri ->
                    var added: Book? = null
                    try {
                        val book = withContext(Dispatchers.IO) { library.importPdf(uri) }
                        added = book
                        books = library.all()
                        if (selectedId == null) {
                            selectedId = book.id
                            rememberBook(book.id)
                        }
                        val processed = withContext(Dispatchers.IO) { library.process(book) }
                        books = library.all()
                        if (processed.status == STATUS_NO_TEXT) {
                            message = "“${processed.title}” não contém texto extraível. OCR não está disponível."
                        }
                    } catch (error: Exception) {
                        if (added != null) {
                            val failed = added.copy(status = STATUS_ERROR, error = error.message.orEmpty())
                            library.update(failed)
                            books = library.all()
                        }
                        message = error.message ?: "Não foi possível importar o documento."
                    }
                }
            }
        }
    }

    // Conteúdo visual principal: cabeçalho e uma das telas de ajustes, biblioteca ou player.
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
            Header(
                showLibrary = showLibrary,
                showSettings = showSettings,
                bookCount = books.size,
                onLibrary = {
                    showLibrary = !showLibrary
                    showSettings = false
                },
                onSettings = {
                    showSettings = !showSettings
                    showLibrary = false
                },
                onImport = { importLauncher.launch(arrayOf("application/pdf")) }
            )
            Spacer(Modifier.height(20.dp))

            if (showSettings) {
                SettingsScreen(
                    colors = appColors,
                    onColorsChanged = onColorsChanged,
                    onBack = { showSettings = false }
                )
            } else if (showLibrary) {
                LibraryScreen(
                    library = library,
                    books = books,
                    selectedId = selectedId,
                    search = search,
                    sortByName = sortByName,
                    onSearch = { search = it },
                    onSort = { sortByName = !sortByName },
                    onSelect = { book ->
                        if (selectedId != book.id) {
                            stopPlayback()
                            selectedId = book.id
                            currentPage = book.currentPage.coerceAtLeast(0)
                            currentParagraph = book.currentParagraph.coerceAtLeast(0)
                            rate = book.speechRate
                        }
                        rememberBook(book.id)
                        refresh(book.copy(lastPlayedAt = System.currentTimeMillis()))
                        showLibrary = false
                    },
                    onRename = { book ->
                        renameBook = book
                        renameValue = book.title
                    },
                    onDelete = { book ->
                        if (selectedId == book.id) {
                            stopPlayback()
                            selectedId = books.firstOrNull { it.id != book.id }?.id
                            rememberBook(selectedId)
                        }
                        library.delete(book)
                        books = library.all()
                    },
                    onProcess = { book ->
                        scope.launch {
                            val processing = book.copy(status = STATUS_PROCESSING, error = "")
                            refresh(processing)
                            try {
                                withContext(Dispatchers.IO) { library.process(processing) }
                                books = library.all()
                            } catch (error: Exception) {
                                val failed = processing.copy(
                                    status = STATUS_ERROR,
                                    error = error.message ?: "Falha ao processar o PDF."
                                )
                                refresh(failed)
                                message = failed.error
                            }
                        }
                    }
                )
            } else if (selectedBook != null) {
                PlayerScreen(
                    book = selectedBook,
                    page = currentPage,
                    pageCount = selectedBook.pageCount,
                    paragraphCount = paragraphs.size,
                    currentParagraph = currentParagraph,
                    isPlaying = isPlaying,
                    isLoading = isLoading,
                    ttsReady = ttsReady,
                    rate = rate,
                    volume = volume,
                    sleepMinutes = sleepMinutes,
                    sleepAtDocumentEnd = sleepAtDocumentEnd,
                    onPlay = { playOrResume() },
                    onPause = { stopPlayback() },
                    onStop = { stopPlayback(reset = true) },
                    onNext = {
                        if (paragraphs.isNotEmpty() && currentParagraph < paragraphs.lastIndex) {
                            navigate(currentPage, currentParagraph + 1)
                        } else {
                            navigate(currentPage + 1, 0)
                        }
                    },
                    onPrevious = {
                        if (currentParagraph > 0) {
                            navigate(currentPage, currentParagraph - 1)
                        } else {
                            if (currentPage > 0) navigate(currentPage - 1, Int.MAX_VALUE)
                        }
                    },
                    onSeekPage = { page ->
                        stopPlayback()
                        currentPage = page
                        currentParagraph = 0
                        loadAndSpeak(page, 0, false)
                    },
                    onRateSelected = { selectedRate ->
                        rate = selectedRate
                        speech.setSpeechRate(selectedRate)
                        val updated = selectedBook.copy(speechRate = selectedRate)
                        refresh(updated)
                    },
                    onVolume = { volume = it },
                    onTimer = { showSleepOptions = true },
                    onVoiceMenu = { showVoiceOptions = true }
                )
            } else {
                EmptyLibrary(onImport = { importLauncher.launch(arrayOf("application/pdf")) })
            }

                if (message.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = message,
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            // Atalho flutuante que abre o texto informativo incluído nos assets do app.
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
                    .size(40.dp)
            ) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(40.dp)
                        .clickable {
                            hasUnreadLog = false
                            showLog = true
                        },
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("i", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
                }
                if (hasUnreadLog) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(13.dp),
                        shape = CircleShape,
                        color = Color(0x66FF3B30)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Surface(
                                modifier = Modifier
                                    .size(9.dp)
                                    .border(1.dp, Color.White, CircleShape),
                                shape = CircleShape,
                                color = Color(0xFFFF3B30)
                            ) {}
                        }
                    }
                }
            }

            if (showLog) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(20.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Log", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                            TextButton(onClick = { showLog = false }) {
                                Text("Fechar")
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        Text(logContent)
                    }
                }
            }
        }
    }

    // Diálogos independentes da tela principal para voz, temporizador e renomeação.
    if (showVoiceOptions) {
        SelectionDialog(
            title = "Vozes disponíveis no dispositivo",
            options = voices.map { voice ->
                "${voice.locale.getDisplayName(Locale.forLanguageTag("pt-BR"))} · ${voice.name}" to voice
            },
            onDismiss = { showVoiceOptions = false },
            onSelect = { value ->
                showVoiceOptions = false
                val voice = value as Voice
                speech.voice = voice
                selectedBook?.let { refresh(it.copy(voiceName = voice.name)) }
                if (isPlaying) {
                    stopPlayback()
                    playOrResume()
                }
            },
            onPreview = { voice ->
                if (isPlaying) stopPlayback()
                speech.voice = voice
                speech.speak(
                    "Olá. Esta é uma prévia da voz selecionada.",
                    TextToSpeech.QUEUE_FLUSH,
                    null,
                    "voice-preview-${System.nanoTime()}"
                )
            },
            footer = if (voices.isEmpty()) "Nenhuma voz foi retornada pelo mecanismo TTS instalado." else null
        )
    }
    if (showSleepOptions) {
        AlertDialog(
            onDismissRequest = { showSleepOptions = false },
            title = { Text("Temporizador de desligamento") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(15, 30, 45, 60).forEach { minutes ->
                        TextButton(onClick = {
                            sleepMinutes = minutes
                            sleepDeadline = System.currentTimeMillis() + minutes * 60_000L
                            sleepAtDocumentEnd = false
                            showSleepOptions = false
                        }) { Text("$minutes minutos") }
                    }
                    TextButton(onClick = {
                        sleepMinutes = 0
                        sleepDeadline = null
                        sleepAtDocumentEnd = true
                        showSleepOptions = false
                    }) { Text("Ao terminar o documento") }
                    TextButton(onClick = {
                        sleepMinutes = 0
                        sleepDeadline = null
                        sleepAtDocumentEnd = false
                        showSleepOptions = false
                    }) { Text("Cancelar temporizador") }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSleepOptions = false }) { Text("Fechar") }
            }
        )
    }
    if (renameBook != null) {
        AlertDialog(
            onDismissRequest = { renameBook = null },
            title = { Text("Renomear documento") },
            text = {
                OutlinedTextField(
                    value = renameValue,
                    onValueChange = { renameValue = it },
                    singleLine = true,
                    label = { Text("Título na biblioteca") }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    renameBook?.let { refresh(it.copy(title = renameValue.trim().ifBlank { it.title })) }
                    renameBook = null
                }) { Text("Salvar") }
            },
            dismissButton = {
                TextButton(onClick = { renameBook = null }) { Text("Cancelar") }
            }
        )
    }
}

@Composable
private fun Header(
    showLibrary: Boolean,
    showSettings: Boolean,
    bookCount: Int,
    onLibrary: () -> Unit,
    onSettings: () -> Unit,
    onImport: () -> Unit
) {
    // Cabeçalho comum às telas, com ações para importar PDF, abrir a biblioteca e ajustar cores.
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "PDF CASSETE",
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Seu acervo, em voz alta",
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Button(
                onClick = onImport,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Text("+ PDF", color = MaterialTheme.colorScheme.onPrimary)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            OutlinedButton(onClick = onLibrary) {
                Text(if (showLibrary) "Player" else "Biblioteca ($bookCount)")
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = onSettings) {
                Text(if (showSettings) "Voltar" else "Cores")
            }
        }
    }
}

private data class ColorChoice(val label: String, val color: Color)

@Composable
private fun SettingsScreen(
    colors: AppColors,
    onColorsChanged: (AppColors) -> Unit,
    onBack: () -> Unit
) {
    // Opções predefinidas de destaque, fundo e texto disponíveis na personalização.
    val accentOptions = listOf(
        ColorChoice(stringResource(R.string.color_orange), Color(0xFFFF9A56)),
        ColorChoice(stringResource(R.string.color_blue), Color(0xFF64B5F6)),
        ColorChoice(stringResource(R.string.color_green), Color(0xFF80CBC4)),
        ColorChoice(stringResource(R.string.color_purple), Color(0xFFCE93D8)),
        ColorChoice(stringResource(R.string.color_red), Color(0xFFEF9A9A))
    )
    val backgroundOptions = listOf(
        ColorChoice(stringResource(R.string.background_default), Color(0xFF101111)),
        ColorChoice(stringResource(R.string.background_black), Color(0xFF000000)),
        ColorChoice(stringResource(R.string.background_warm), Color(0xFF25221E)),
        ColorChoice(stringResource(R.string.background_light), Color(0xFFF1F1F1))
    )
    val textOptions = listOf(
        ColorChoice(stringResource(R.string.text_default), Color(0xFFF3F0EA)),
        ColorChoice(stringResource(R.string.text_white), Color(0xFFFFFFFF)),
        ColorChoice(stringResource(R.string.text_gray), Color(0xFFBDBDBD)),
        ColorChoice(stringResource(R.string.text_black), Color(0xFF171717))
    )

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.settings_title),
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold
            )
            TextButton(onClick = onBack) { Text(stringResource(R.string.settings_back)) }
        }
        ColorSettingsRow(
            label = stringResource(R.string.settings_accent),
            options = accentOptions,
            selected = colors.accent,
            onSelect = { onColorsChanged(colors.copy(accent = it)) }
        )
        ColorSettingsRow(
            label = stringResource(R.string.settings_background),
            options = backgroundOptions,
            selected = colors.background,
            onSelect = { onColorsChanged(colors.copy(background = it)) }
        )
        ColorSettingsRow(
            label = stringResource(R.string.settings_text),
            options = textOptions,
            selected = colors.text,
            onSelect = { onColorsChanged(colors.copy(text = it)) }
        )
    }
}

@Composable
private fun ColorSettingsRow(
    label: String,
    options: List<ColorChoice>,
    selected: Color,
    onSelect: (Color) -> Unit
) {
    // Exibe uma categoria de cores e destaca visualmente a opção selecionada.
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, fontWeight = FontWeight.SemiBold)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            options.forEach { option ->
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onSelect(option.color) },
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Surface(
                        modifier = Modifier
                            .size(38.dp)
                            .border(
                                width = if (selected == option.color) 3.dp else 1.dp,
                                color = if (selected == option.color) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                                },
                                shape = CircleShape
                            ),
                        shape = CircleShape,
                        color = option.color
                    ) {}
                    Text(
                        option.label,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyLibrary(onImport: () -> Unit) {
    // Estado inicial da biblioteca com uma ação direta para importar o primeiro PDF.
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(vertical = 48.dp, horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("Sua próxima leitura começa aqui", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Text("Importe um PDF para extrair o texto e ouvir com a voz do seu dispositivo.")
            Button(onClick = onImport) { Text("Importar PDF") }
        }
    }
}

@Composable
private fun PlayerScreen(
    book: Book,
    page: Int,
    pageCount: Int,
    paragraphCount: Int,
    currentParagraph: Int,
    isPlaying: Boolean,
    isLoading: Boolean,
    ttsReady: Boolean,
    rate: Float,
    volume: Float,
    sleepMinutes: Int,
    sleepAtDocumentEnd: Boolean,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeekPage: (Int) -> Unit,
    onRateSelected: (Float) -> Unit,
    onVolume: (Float) -> Unit,
    onTimer: () -> Unit,
    onVoiceMenu: () -> Unit
) {
    // Player: status, capa ilustrada, posição do documento e controles de áudio.
    var menuRate by remember { mutableStateOf(false) }
    var seekPage by remember(page) { mutableFloatStateOf(page.toFloat()) }
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = when (book.status) {
                STATUS_PROCESSING -> "PREPARANDO DOCUMENTO"
                STATUS_PENDING -> "PROCESSAMENTO PENDENTE"
                STATUS_NO_TEXT -> "PDF SEM TEXTO EXTRAÍVEL"
                STATUS_ERROR -> "FALHA NO PROCESSAMENTO"
                else -> if (book.completed) "LEITURA CONCLUÍDA" else "EM REPRODUÇÃO · TEXTO ESTIMADO"
            },
            color = MaterialTheme.colorScheme.secondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.4.sp
        )
        Spacer(Modifier.height(8.dp))
        Text(
            book.title,
            fontSize = 25.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            book.author.ifBlank { book.fileName },
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(16.dp))
        CassetteIllustration(
            isPlaying = isPlaying,
            isLoading = isLoading,
            completed = book.completed,
            modifier = Modifier.fillMaxWidth().height(204.dp)
        )
        Spacer(Modifier.height(10.dp))
        Text(
            if (pageCount > 0) "Página ${page + 1} de $pageCount  ·  Trecho ${if (paragraphCount == 0) 0 else currentParagraph + 1}" else book.error,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
            fontSize = 13.sp
        )
        if (pageCount > 1) {
            Slider(
                value = seekPage.coerceIn(0f, (pageCount - 1).toFloat()),
                onValueChange = { seekPage = it },
                onValueChangeFinished = { onSeekPage(seekPage.toInt()) },
                valueRange = 0f..(pageCount - 1).toFloat(),
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Progresso estimado pela página",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    fontSize = 11.sp
                )
                val percent = if (book.completed) 100 else if (pageCount > 0) (((seekPage.coerceIn(0f, (pageCount - 1).toFloat()) + 1f) / pageCount.toFloat()) * 100f).toInt().coerceIn(0, 100) else 0
                Text(
                    "$percent%",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    fontSize = 11.sp
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onPrevious, enabled = book.status == STATUS_READY) {
                Text("‹ Trecho", color = MaterialTheme.colorScheme.onSurface)
            }
            Spacer(Modifier.width(10.dp))
            Button(
                onClick = if (isPlaying) onPause else onPlay,
                enabled = book.status == STATUS_READY && !isLoading && ttsReady,
                modifier = Modifier.size(72.dp),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text(
                    if (isPlaying) "Ⅱ" else "▶",
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontSize = 24.sp
                )
            }
            Spacer(Modifier.width(10.dp))
            TextButton(onClick = onNext, enabled = book.status == STATUS_READY) {
                Text("Trecho ›", color = MaterialTheme.colorScheme.onSurface)
            }
        }
        TextButton(onClick = onStop, enabled = book.status == STATUS_READY) {
            Text(
                "Parar e voltar ao início",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
            )
        }
        Spacer(Modifier.height(8.dp))
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Voz e reprodução", fontWeight = FontWeight.SemiBold)
                    Box {
                        OutlinedButton(onClick = { menuRate = true }) { Text("${rate}x") }
                        DropdownMenu(expanded = menuRate, onDismissRequest = { menuRate = false }) {
                            listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f).forEach { option ->
                                DropdownMenuItem(
                                    text = { Text("${option}x") },
                                    onClick = {
                                        menuRate = false
                                        onRateSelected(option)
                                    }
                                )
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Volume")
                    Slider(value = volume, onValueChange = onVolume, modifier = Modifier.width(190.dp))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    OutlinedButton(onClick = onVoiceMenu) { Text("Escolher voz") }
                    OutlinedButton(onClick = onTimer) {
                        Text(
                            when {
                                sleepMinutes > 0 -> "Timer · ${sleepMinutes}m"
                                sleepAtDocumentEnd -> "Timer · fim"
                                else -> "Timer"
                            }
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "As vozes e idiomas exibidos dependem do mecanismo TTS instalado no Android.",
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            fontSize = 11.sp
        )
    }
}

@Composable
private fun LibraryScreen(
    library: BookLibrary,
    books: List<Book>,
    selectedId: String?,
    search: String,
    sortByName: Boolean,
    onSearch: (String) -> Unit,
    onSort: () -> Unit,
    onSelect: (Book) -> Unit,
    onRename: (Book) -> Unit,
    onDelete: (Book) -> Unit,
    onProcess: (Book) -> Unit
) {
    // Biblioteca com pesquisa, ordenação e ações por documento.
    Column(modifier = Modifier.fillMaxWidth()) {
        Text("Sua biblioteca", fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = search,
            onValueChange = onSearch,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Pesquisar documentos") }
        )
        TextButton(onClick = onSort) {
            Text(if (sortByName) "Ordenação: nome" else "Ordenação: recentes")
        }
        val filtered = books
            .filter { it.title.contains(search, ignoreCase = true) || it.author.contains(search, ignoreCase = true) }
            .let {
                list ->
                if (sortByName) list.sortedBy { it.title.lowercase() }
                else list.sortedByDescending { maxOf(it.lastPlayedAt, it.importedAt) }
            }
        if (filtered.isEmpty()) {
            Text(if (books.isEmpty()) "Ainda não há PDFs importados." else "Nenhum documento encontrado.")
        }
        filtered.forEach { book ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (book.id == selectedId) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                    } else {
                        MaterialTheme.colorScheme.surface
                    }
                ),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    BookCover(book = book, library = library)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            book.title,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable { onSelect(book) }
                        )
                        Text(
                            "${book.pageCount} páginas · ${formatBytes(book.sizeBytes)} · ${bookStatus(book)}",
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                            fontSize = 12.sp
                        )
                        if (book.lastPlayedAt > 0 || book.completed) {
                            Text(
                                if (book.completed) "Concluído" else "Retomar na página ${book.currentPage + 1}",
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 12.sp
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { onSelect(book) }) {
                                Text(if (book.currentPage > 0) "Continuar ouvindo" else "Abrir")
                            }
                            if (book.status == STATUS_PENDING || book.status == STATUS_ERROR) {
                                TextButton(onClick = { onProcess(book) }) { Text("Processar novamente") }
                            }
                            TextButton(onClick = { onRename(book) }) { Text("Renomear") }
                            TextButton(onClick = { onDelete(book) }) { Text("Remover") }
                        }
                        if (book.status == STATUS_NO_TEXT) {
                            Text(
                                "Pode ser uma digitalização; o reconhecimento OCR não está disponível.",
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BookCover(book: Book, library: BookLibrary) {
    // Decodifica a capa fora da thread principal e mostra um substituto se ela não estiver disponível.
    var bitmap by remember(book.id) {
        mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)
    }
    var unavailable by remember(book.id) { mutableStateOf(false) }
    LaunchedEffect(book.id) {
        try {
            val loaded = withContext(Dispatchers.IO) {
                BitmapFactory.decodeFile(library.coverFile(book.id).absolutePath)
            }
            if (loaded == null) {
                unavailable = true
            } else {
                bitmap = loaded.asImageBitmap()
            }
        } catch (_: Exception) {
            unavailable = true
        }
    }
    Surface(
        modifier = Modifier.width(72.dp).height(104.dp),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!,
                contentDescription = "Capa de ${book.title}",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    if (unavailable) "Capa indisponível" else "PDF",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    fontSize = 10.sp
                )
            }
        }
    }
}

@Composable
private fun CassetteIllustration(
    isPlaying: Boolean,
    isLoading: Boolean,
    completed: Boolean,
    modifier: Modifier = Modifier
) {
    // Ilustração vetorial em Canvas; os carretéis giram enquanto a leitura está ativa.
    var angle by remember { mutableFloatStateOf(0f) }
    val accentColor = MaterialTheme.colorScheme.primary
    val completionColor = MaterialTheme.colorScheme.secondary
    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            var lastFrame = 0L
            while (true) {
                withFrameNanos { frame ->
                    if (lastFrame != 0L) angle = (angle + (frame - lastFrame) / 30_000_000f) % 360f
                    lastFrame = frame
                }
            }
        }
    }
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val cassetteWidth = w * 0.83f
        val cassetteHeight = h * 0.78f
        val left = (w - cassetteWidth) / 2f
        val top = (h - cassetteHeight) / 2f
        drawRoundRect(
            color = Color(0xFF343633),
            topLeft = androidx.compose.ui.geometry.Offset(left, top),
            size = androidx.compose.ui.geometry.Size(cassetteWidth, cassetteHeight),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(22f)
        )
        drawRoundRect(
            color = Color(0xFF5A5D57),
            topLeft = androidx.compose.ui.geometry.Offset(left + 8, top + 8),
            size = androidx.compose.ui.geometry.Size(cassetteWidth - 16, cassetteHeight - 16),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(17f),
            style = Stroke(width = 2f)
        )
        drawRoundRect(
            color = Color(0xFF171A19),
            topLeft = androidx.compose.ui.geometry.Offset(left + cassetteWidth * 0.13f, top + cassetteHeight * 0.18f),
            size = androidx.compose.ui.geometry.Size(cassetteWidth * 0.74f, cassetteHeight * 0.48f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(16f)
        )
        val centerY = top + cassetteHeight * 0.42f
        val radius = cassetteHeight * 0.145f
        val centers = listOf(
            androidx.compose.ui.geometry.Offset(left + cassetteWidth * 0.32f, centerY),
            androidx.compose.ui.geometry.Offset(left + cassetteWidth * 0.68f, centerY)
        )
        drawLine(
            color = accentColor,
            start = androidx.compose.ui.geometry.Offset(centers[0].x + radius, centerY),
            end = androidx.compose.ui.geometry.Offset(centers[1].x - radius, centerY),
            strokeWidth = 5f
        )
        centers.forEach { center ->
            drawCircle(Color(0xFFD8D5C9), radius, center)
            drawCircle(Color(0xFF3B3D39), radius * 0.74f, center)
            drawCircle(Color(0xFFB67A3F), radius * 0.27f, center)
            for (spoke in 0 until 6) {
                val radians = (angle + spoke * 60f) * PI.toFloat() / 180f
                drawLine(
                    color = Color(0xFF969890),
                    start = androidx.compose.ui.geometry.Offset(
                        center.x + cos(radians) * radius * 0.33f,
                        center.y + sin(radians) * radius * 0.33f
                    ),
                    end = androidx.compose.ui.geometry.Offset(
                        center.x + cos(radians) * radius * 0.68f,
                        center.y + sin(radians) * radius * 0.68f
                    ),
                    strokeWidth = 3f,
                    cap = StrokeCap.Round
                )
            }
        }
        drawRoundRect(
            color = if (isPlaying) accentColor else Color(0xFF85877F),
            topLeft = androidx.compose.ui.geometry.Offset(left + cassetteWidth * 0.28f, top + cassetteHeight * 0.76f),
            size = androidx.compose.ui.geometry.Size(cassetteWidth * 0.44f, cassetteHeight * 0.055f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(5f)
        )
        if (isLoading) {
            drawCircle(accentColor, radius = 5f, center = androidx.compose.ui.geometry.Offset(w / 2, h * 0.88f))
        } else if (completed) {
            drawCircle(completionColor, radius = 5f, center = androidx.compose.ui.geometry.Offset(w / 2, h * 0.88f))
        }
    }
}

@Composable
private fun <T : Any> SelectionDialog(
    title: String,
    options: List<Pair<String, T>>,
    onDismiss: () -> Unit,
    onSelect: (T) -> Unit,
    onPreview: ((T) -> Unit)? = null,
    footer: String? = null
) {
    // Diálogo genérico para selecionar um valor e, quando solicitado, pré-visualizá-lo.
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.height(320.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                options.forEach { (label, value) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { onSelect(value) }, modifier = Modifier.weight(1f)) {
                            Text(label, modifier = Modifier.fillMaxWidth())
                        }
                        if (onPreview != null) {
                            TextButton(onClick = { onPreview(value) }) { Text("Ouvir") }
                        }
                    }
                }
                footer?.let { Text(it, color = Color.Gray) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fechar") } }
    )
}

private fun splitForSpeech(text: String): List<String> {
    // Junta quebras simples, preserva parágrafos e limita o tamanho aceito em cada fala do TTS.
    val paragraphs = text
        .replace(Regex("[\\t\\u00A0]+"), " ")
        .replace(Regex("(?<=\\p{L})-\\n\\s*(?=\\p{Ll})"), "")
        .replace(Regex("(?<!\\n)\\n(?!\\n)"), " ")
        .split(Regex("\\n\\s*\\n"))
        .map(String::trim)
        .filter(String::isNotBlank)
    return paragraphs.flatMap { paragraph ->
        if (paragraph.length <= 700) return@flatMap listOf(paragraph)
        val chunks = mutableListOf<String>()
        var chunk = StringBuilder()
        paragraph.split(Regex("\\s+")).forEach { word ->
            if (chunk.isNotEmpty() && chunk.length + word.length + 1 > 650) {
                chunks += chunk.toString()
                chunk = StringBuilder()
            }
            if (word.length > 650) {
                if (chunk.isNotEmpty()) {
                    chunks += chunk.toString()
                    chunk = StringBuilder()
                }
                chunks += word.chunked(650)
            } else {
                if (chunk.isNotEmpty()) chunk.append(' ')
                chunk.append(word)
            }
        }
        if (chunk.isNotEmpty()) chunks += chunk.toString()
        chunks
    }
}

/** Traduz o estado interno do processamento para um rótulo curto na biblioteca. */
private fun bookStatus(book: Book): String = when (book.status) {
    STATUS_PROCESSING -> "processando"
    STATUS_PENDING -> "pendente"
    STATUS_READY -> if (book.completed) "concluído" else "pronto"
    STATUS_NO_TEXT -> "sem texto"
    STATUS_ERROR -> "erro"
    else -> book.status
}

/** Formata o tamanho do PDF em KB ou MB para exibição. */
private fun formatBytes(bytes: Long): String =
    if (bytes < 1024 * 1024) "${(bytes / 1024f).toInt()} KB"
    else String.format(Locale.getDefault(), "%.1f MB", bytes / (1024f * 1024f))

package com.example.myapplication

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Serviço em primeiro plano que mantém os controles de mídia disponíveis fora da Activity.
 * A reprodução TTS continua na Activity; este serviço encaminha comandos e publica o estado.
 */
class AudioPlaybackService : Service() {
    // Sessão e recursos de energia/áudio mantidos enquanto a leitura está ativa.
    private lateinit var mediaSession: MediaSessionCompat
    private var wakeLock: PowerManager.WakeLock? = null
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var title: String = ""
    private var isPlaying = false

    override fun onCreate() {
        super.onCreate()
        // Mantém a CPU ativa durante a narração e prepara o acesso ao foco de áudio.
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "PDFCassete::AudioPlaybackWakeLock"
        ).apply {
            setReferenceCounted(false)
        }
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager

        // Integra os botões do sistema (incluindo controles de mídia) com os comandos do app.
        mediaSession = MediaSessionCompat(this, "PDF Cassette").apply {
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = sendPlaybackCommand(ACTION_PLAY)
                override fun onPause() = sendPlaybackCommand(ACTION_PAUSE)
                override fun onSkipToNext() = sendPlaybackCommand(ACTION_NEXT)
                override fun onSkipToPrevious() = sendPlaybackCommand(ACTION_PREVIOUS)
            })
            isActive = true
        }
    }

    // Serviço iniciado, não vinculado diretamente por outros componentes.
    override fun onBind(intent: Intent?): IBinder? = null

    /** Processa comandos recebidos e atualiza notificação, sessão e recursos de áudio. */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY, ACTION_PAUSE, ACTION_NEXT, ACTION_PREVIOUS -> {
                sendPlaybackCommand(intent.action!!)
            }
            ACTION_UPDATE -> {
                title = intent.getStringExtra(EXTRA_TITLE) ?: title
                isPlaying = intent.getBooleanExtra(EXTRA_IS_PLAYING, isPlaying)
                updateWakeLockAndAudioFocus()
                updateMediaSession()
                startInForeground(buildNotification())
            }
            else -> {
                title = intent?.getStringExtra(EXTRA_TITLE) ?: getString(R.string.app_name)
                isPlaying = true
                createNotificationChannel()
                updateWakeLockAndAudioFocus()
                updateMediaSession()
                startInForeground(buildNotification())
            }
        }
        return START_NOT_STICKY
    }

    /** Adquire ou libera o wake lock e o foco conforme o estado de reprodução. */
    private fun updateWakeLockAndAudioFocus() {
        if (isPlaying) {
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(12 * 60 * 60 * 1000L)
            }
            requestAudioFocus()
        } else {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
            abandonAudioFocus()
        }
    }

    /** Solicita foco de áudio usando a API disponível na versão atual do Android. */
    private fun requestAudioFocus() {
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (audioFocusRequest == null) {
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attrs)
                    .setAcceptsDelayedFocusGain(true)
                    .setOnAudioFocusChangeListener { focusChange ->
                        if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
                            sendPlaybackCommand(ACTION_PAUSE)
                        }
                    }
                    .build()
            }
            audioFocusRequest?.let { am.requestAudioFocus(it) }
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(
                null,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
        }
    }

    /** Devolve ao sistema o foco de áudio previamente solicitado. */
    private fun abandonAudioFocus() {
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(null)
        }
    }

    /** Inicia/atualiza o serviço em primeiro plano com o tipo de reprodução de mídia. */
    private fun startInForeground(notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /** Publica metadados do livro e ações/estado atuais para os controles do Android. */
    private fun updateMediaSession() {
        mediaSession.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, getString(R.string.app_name))
                .build()
        )
        val actions = PlaybackStateCompat.ACTION_PLAY or
            PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or
            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(
                    if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                    PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN,
                    if (isPlaying) 1f else 0f
                )
                .build()
        )
    }

    /** Cria a notificação persistente com atalhos para navegar e controlar a narração. */
    private fun buildNotification(): android.app.Notification {
        val openAppIntent = Intent(this, MainActivity::class.java)
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText(getString(R.string.reading_notification_text))
            .setContentIntent(contentPendingIntent)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(isPlaying)
            .addAction(
                android.R.drawable.ic_media_previous,
                getString(R.string.previous_action),
                commandPendingIntent(ACTION_PREVIOUS)
            )
            .addAction(
                if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                getString(if (isPlaying) R.string.pause_action else R.string.play_action),
                commandPendingIntent(if (isPlaying) ACTION_PAUSE else ACTION_PLAY)
            )
            .addAction(
                android.R.drawable.ic_media_next,
                getString(R.string.next_action),
                commandPendingIntent(ACTION_NEXT)
            )
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }

    /** Constrói um PendingIntent que entrega uma ação de mídia a este serviço. */
    private fun commandPendingIntent(action: String): PendingIntent {
        val intent = Intent(this, AudioPlaybackService::class.java).setAction(action)
        return PendingIntent.getService(
            this,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** Encaminha uma ação da notificação/sessão à Activity por broadcast não exportado. */
    private fun sendPlaybackCommand(command: String) {
        sendBroadcast(
            Intent(ACTION_PLAYBACK_COMMAND)
                .setPackage(packageName)
                .putExtra(EXTRA_COMMAND, command)
        )
    }

    /** Registra o canal de notificação exigido pelo Android 8 ou posterior. */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.reading_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        // Libera todos os recursos mantidos pelo serviço antes de ser destruído.
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        abandonAudioFocus()
        mediaSession.release()
        super.onDestroy()
    }

    companion object {
        // Ações e extras formam o contrato de comunicação entre Activity e serviço.
        const val ACTION_PLAYBACK_COMMAND = "com.example.myapplication.PLAYBACK_COMMAND"
        const val ACTION_PLAY = "com.example.myapplication.action.PLAY"
        const val ACTION_PAUSE = "com.example.myapplication.action.PAUSE"
        const val ACTION_NEXT = "com.example.myapplication.action.NEXT"
        const val ACTION_PREVIOUS = "com.example.myapplication.action.PREVIOUS"
        const val EXTRA_COMMAND = "command"

        private const val ACTION_UPDATE = "com.example.myapplication.action.UPDATE"
        private const val CHANNEL_ID = "audio_playback"
        private const val NOTIFICATION_ID = 1
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_IS_PLAYING = "is_playing"

        /** Inicia o serviço em primeiro plano para uma nova sessão de leitura. */
        fun start(context: Context, title: String) {
            val intent = Intent(context, AudioPlaybackService::class.java)
                .putExtra(EXTRA_TITLE, title)
            ContextCompat.startForegroundService(context, intent)
        }

        /** Atualiza título e estado do player sem criar outra sessão de serviço. */
        fun update(context: Context, title: String, isPlaying: Boolean) {
            val intent = Intent(context, AudioPlaybackService::class.java)
                .setAction(ACTION_UPDATE)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_IS_PLAYING, isPlaying)
            context.startService(intent)
        }

        /** Encerra o serviço de reprodução. */
        fun stop(context: Context) {
            context.stopService(Intent(context, AudioPlaybackService::class.java))
        }
    }
}

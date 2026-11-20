# PDF Cassete

Aplicativo Android para importar PDFs, extrair o texto e ouvi-lo com o mecanismo
de conversao de texto em fala (TTS) instalado no dispositivo. A interface e
construida com Kotlin e Jetpack Compose. O projeto mantem a implementacao
concentrada no modulo `:app`, sem camada de repositorio ou navegacao separada.

## Como o aplicativo funciona

1. A pessoa seleciona um ou mais PDFs pelo seletor de documentos do Android.
2. `BookLibrary` copia cada arquivo para o armazenamento privado do aplicativo
   e registra seus dados na biblioteca.
3. O PDFBox le os metadados e extrai o texto de cada pagina. A primeira pagina
   tambem e renderizada para ser usada como capa.
4. `MainActivity` divide o texto em trechos adequados para o TTS e apresenta o
   player, a biblioteca e as configuracoes em Compose.
5. O `TextToSpeech` do Android narra os trechos e o `AudioPlaybackService`
   mantem os controles de reproducao disponiveis na notificacao e na tela
   bloqueada.

PDFs que contem somente imagens podem ser importados, mas nao podem ser narrados
nesta versao: reconhecimento OCR nao esta implementado.

## Codigo Kotlin

### `app/src/main/java/com/example/myapplication/MainActivity.kt`

E a atividade principal e coordena a interface, a narracao e o ciclo de vida:

- **`MainActivity`** configura a janela edge-to-edge, registra o receptor de
  comandos do player e inicializa o TTS em portugues do Brasil. Quando as vozes
  ficam prontas, disponibiliza a lista para a interface. Ao pausar, salva o
  progresso; ao destruir, remove o receptor, encerra o servico e libera o TTS.
- **`PreparedPage` e `AppColors`** sao estruturas auxiliares para guardar uma
  pagina ja preparada para narracao e as tres cores personalizaveis do tema.
- **`CassetteReadApp`** e o ponto central da interface Compose. Mantem estados
  como livro selecionado, pagina, trecho, velocidade, volume, temporizador,
  busca, dialogs e estados de carregamento. Quando esses estados mudam, Compose
  atualiza a tela correspondente.
- **Funcoes locais de reproducao**:
  - `refresh` atualiza a lista de livros, opcionalmente persistindo um livro.
  - `rememberBook` guarda qual livro deve ser reaberto.
  - `saveProgress` persiste pagina, trecho, velocidade e data da ultima
    reproducao.
  - `stopPlayback` interrompe a fala e pausa ou reinicia a reproducao.
  - `speakText` configura voz, velocidade e volume, atualiza o servico e envia
    um trecho ao TTS.
  - `loadAndSpeak` carrega texto em uma coroutine, pula paginas sem texto e
    inicia a narracao ou apenas posiciona o player.
  - `playOrResume` valida a disponibilidade do TTS e o estado do PDF antes de
    retomar ou iniciar a leitura.
  - `navigate` interrompe o trecho atual e carrega o destino.
- **Efeitos `LaunchedEffect`** carregam trechos fora da thread principal,
  pre-carregam a proxima pagina com texto, salvam o progresso periodicamente,
  aplicam o temporizador e recuperam livros cujo processamento foi interrompido.
- **Importacao de PDFs** usa `OpenMultipleDocuments`. A copia e o processamento
  rodam em `Dispatchers.IO`; os erros sao exibidos na tela e registrados no
  estado do livro quando possivel.
- **Composables da tela**:
  - `Header`: cabecalho e acesso a importacao, biblioteca e cores.
  - `SettingsScreen` e `ColorSettingsRow`: selecao das cores do aplicativo.
  - `EmptyLibrary`: orientacao exibida quando ainda nao ha PDFs.
  - `PlayerScreen`: estado do documento, progresso e controles de reproducao.
  - `LibraryScreen` e `BookCover`: busca, ordenacao, selecao e a capa de cada
    documento.
  - `CassetteIllustration`: desenho da fita e animacao dos rolos em `Canvas`.
  - `SelectionDialog`: lista reutilizavel, usada para escolher e testar vozes.
- **Funcoes auxiliares**:
  - `splitForSpeech` normaliza quebras de linha e divide paragrafos longos em
    trechos menores para o TTS.
  - `bookStatus` transforma os estados internos em textos para a biblioteca.
  - `formatBytes` formata o tamanho do arquivo para exibicao.

### `app/src/main/java/com/example/myapplication/BookLibrary.kt`

Responsavel pelo modelo do documento e pelas operacoes de biblioteca e PDF:

- **`Book`** guarda identidade, nome, autor, paginas, tamanho, progresso,
  preferencias de voz e estado de processamento.
- **Constantes `STATUS_*`** indicam se um livro esta sendo processado, pronto,
  sem texto extraivel, com erro ou aguardando processamento.
- **`BookLibrary`** usa o armazenamento privado `filesDir/cassette_books` para
  os PDFs, textos extraidos e capas. Os metadados da biblioteca ficam em
  `SharedPreferences`, serializados como JSON.
- **`importPdf`** cria um identificador, copia o arquivo com limite de 100 MB,
  confere a assinatura `%PDF-` e cria o registro inicial. Se a importacao falha,
  remove a pasta parcial e propaga o erro.
- **`process`** le titulo e autor dos metadados quando disponiveis, extrai o
  texto pagina por pagina com PDFBox, define se o documento tem texto e prepara
  a capa.
- **`renderCover` e `coverFile`** renderizam a primeira pagina com `PdfRenderer`
  e gravam a imagem na pasta do livro.
- **`pageText`** le o texto salvo de uma pagina; **`update`**, **`delete`** e
  funcoes privadas de persistencia mantem os arquivos e registros sincronizados.
- **`decodeBooks`** reconstroi objetos `Book` do JSON e fornece nomes
  substitutos quando os metadados estao vazios ou invalidos.

### `app/src/main/java/com/example/myapplication/AudioPlaybackService.kt`

Servico Android em primeiro plano que integra a narracao com os controles de
midia do sistema:

- **`onCreate`** prepara o bloqueio parcial de CPU, o gerenciador de audio e a
  sessao de midia; botoes de reproducao da sessao encaminham comandos para a
  atividade.
- **`onStartCommand`** recebe pedidos de iniciar, atualizar, reproduzir, pausar,
  avancar e voltar.
- **`updateWakeLockAndAudioFocus`** mantem a CPU ativa durante a leitura e pede
  foco de audio; ao pausar, libera ambos.
- **`requestAudioFocus` e `abandonAudioFocus`** usam as APIs adequadas a versao
  do Android. Uma perda total de foco envia um comando de pausa.
- **`updateMediaSession`** publica titulo e estado da leitura para os controles
  de midia do sistema.
- **`buildNotification`** cria a notificacao com botoes anterior, pausar ou
  reproduzir e proximo. `commandPendingIntent` e `sendPlaybackCommand` conectam
  esses botoes a atividade.
- **`startInForeground`** declara o tipo de servico de reproducao nas versoes
  recentes do Android. `createNotificationChannel` registra o canal da
  notificacao no Android 8 ou posterior.
- **`onDestroy`** libera o wake lock, o foco de audio e a sessao de midia.
- **`companion object`** centraliza as acoes e extras usados na comunicacao com
  a atividade, alem dos metodos `start`, `update` e `stop`.

### Tema e recursos visuais

- `ui/theme/Theme.kt`: cria o esquema Material 3 e aplica as cores escolhidas.
- `ui/theme/Color.kt`: define cores de paleta que podem ser reutilizadas.
- `ui/theme/Type.kt`: configura a tipografia padrao do Material.
- `res/values/strings.xml`: concentra nomes do app, textos da notificacao e
  nomes das opcoes de cores.
- `res/mipmap-*` e `res/drawable`: icones do aplicativo e imagens utilizadas
  pela interface.

## Configuracao Android e Gradle

- `app/src/main/AndroidManifest.xml` declara `MainActivity` como tela inicial e
  registra `AudioPlaybackService` como servico de reproducao. Tambem declara as
  permissoes de wake lock e servico em primeiro plano, e a consulta ao servico
  TTS do Android.
- `app/build.gradle.kts` configura o modulo Android, Kotlin/Compose, SDKs e
  dependencias; as versoes ficam centralizadas em `gradle/libs.versions.toml`.
- `build.gradle.kts` declara configuracoes de plugins compartilhadas no projeto.
- `settings.gradle.kts` define os repositorios, o nome do projeto e inclui o
  modulo `:app`.

## Compilar e testar

No Windows, a partir da raiz do repositorio:

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat test
```

Para executar testes instrumentados, e necessario conectar um dispositivo ou
iniciar um emulador:

```powershell
.\gradlew.bat connectedDebugAndroidTest
```

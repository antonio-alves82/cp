# Classic Player

Player de música para Android com visual iOS e layout inspirado no iPod clássico:
barra de título em degradê, listas com seta (›), seleção em azul, tela "Reproduzindo"
com capa à esquerda e uma **click wheel** na parte de baixo.

## Como usar

- **Girar o anel** com o dedo: rola as listas. Na tela "Reproduzindo" ajusta o volume.
- **Botão central**: abre o item selecionado. Em "Reproduzindo" liga/desliga o modo de avanço (a roda passa a navegar dentro da faixa).
- **MENU** (topo): volta. **⏭ / ⏮** (laterais): próxima / anterior. **⏯** (embaixo): tocar / pausar.
- Também dá para tocar direto nas linhas das listas.
- **Pasta de músicas**: na primeira abertura o app pede a pasta. Para trocar depois:
  *Configurações → Pasta de músicas*. Subpastas são lidas automaticamente.
  *Configurações → Atualizar biblioteca* relê a pasta quando você adicionar músicas.
- Menus: Músicas, Artistas, Álbuns, Aleatório, Configurações (pasta, aleatório, repetir).
- Toca em segundo plano, com notificação de mídia, tela de bloqueio e botões de fone.

Formatos: tudo que o Android reproduz nativamente (MP3, M4A/AAC, FLAC, OGG, OPUS, WAV...).

## Gerar o APK

### Opção A — GitHub Actions (sem instalar nada)
1. Crie um repositório no GitHub e envie esta pasta (branch `main`).
2. Aba **Actions → Build APK** (roda sozinho a cada push, ou use *Run workflow*).
3. Ao terminar, baixe o artefato **ClassicPlayer-apk** → contém `app-release.apk`.

### Opção B — Android Studio
1. *File → Open* e escolha esta pasta.
2. Aguarde o Gradle sincronizar.
3. *Build → Build Bundle(s) / APK(s) → Build APK(s)*.

### Opção C — Linha de comando
Requer JDK 17 e Android SDK (plataforma 34): `./gradlew assembleRelease`
→ `app/build/outputs/apk/release/app-release.apk`

## Instalar no celular
Copie o `.apk` para o aparelho, abra e permita "instalar apps de fontes desconhecidas".
O APK é assinado com a chave de debug (serve para uso pessoal, não para a Play Store).

Requisitos: Android 8.0 (API 26) ou superior.

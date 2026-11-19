# PT Gravador

Gravador de tela Android próprio, sem anúncios, sem marca d'água, sem bibliotecas de terceiros.

- Saída fixa **1080×1920 (9:16)**, MP4 H.264 — pronto para Reels / TikTok / Shorts
- **Preencher**: corta um pouco do topo e da base da tela (geralmente a barra de status e a de navegação) para ocupar o 9:16 inteiro
- **Ajustar**: mostra a tela inteira, com faixas pretas finas nas laterais
- 30 ou 60 fps • 8 / 12 / 20 Mbps
- Narração pelo microfone (opcional)
- Contagem de 3 s, botão PARAR na notificação
- Vídeos em **Filmes/PT Gravador** (aparecem na Galeria)

Requer Android 10 ou superior.

## Gerar o APK pelo GitHub

1. Crie um repositório (de preferência **privado**) e envie esta pasta inteira, incluindo `.github/` e `keystore/`.
2. Abra a aba **Actions**. A compilação começa sozinha a cada push, ou clique em **Gerar APK → Run workflow**.
3. Quando terminar (cerca de 3 a 5 min), abra a execução e baixe **PTGravador-APK** em *Artifacts*. É um .zip com o APK dentro.
4. Passe o APK para o celular, toque nele e permita "instalar apps desconhecidos".

As versões novas instalam por cima da anterior, porque o APK é sempre assinado com a mesma chave (`keystore/ptgravador.jks`).

## Uso

Toque em **GRAVAR**, escolha "Tela inteira" na janela do Android e grave. Para parar, toque em **PARAR** na notificação, no chip vermelho da barra de status, ou abra o app.

## Limitações desta versão

- O áudio **interno** (som dos apps/jogos) ainda não é gravado, só o microfone. Isso fica para a v2, com AudioPlaybackCapture.
- O app grava em retrato. Se você girar o celular durante a gravação, a imagem fica em "Ajustar".

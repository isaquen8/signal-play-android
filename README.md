# Signal Play

Player Android direto para sinais SRT, HLS, RTMP, RTP e UDP. Feito para abrir um endereço, conferir o sinal e colocar a imagem em tela cheia sem menus desnecessários.

## Formatos de endereço

| Protocolo | Exemplo |
|---|---|
| SRT listener | `srt://0.0.0.0:9000?mode=listener` |
| SRT caller | `srt://192.168.1.20:9000?mode=caller&latency=200` |
| HLS | `https://servidor/canal/playlist.m3u8` |
| RTMP | `rtmp://servidor/live/canal` |
| RTP listener | `rtp://@:5004` |
| UDP listener | `udp://@:5000` |

## Compilar

Abra a pasta no Android Studio, aguarde a sincronização e execute em um aparelho Android 6.0 ou mais recente. Pela linha de comando, use `gradle assembleDebug` caso não tenha o Gradle Wrapper. O APK ficará em `app/build/outputs/apk/debug/`.

## Teste de bancada recomendado

Use outra máquina na mesma rede executando VLC ou FFmpeg como origem. Valide cada protocolo por pelo menos 10 minutos, alternando Wi‑Fi e 4G/5G quando aplicável. RTP e UDP multicast podem depender das configurações do roteador; SRT listener exige que a porta esteja acessível.

## Privacidade

O app solicita somente acesso à internet, estado da rede e bloqueio temporário de suspensão da tela. Os endereços recentes ficam apenas no aparelho.

O mecanismo de reprodução usa LibVLC, distribuído sob LGPL 2.1 ou posterior. Consulte `THIRD_PARTY_NOTICES.md`.

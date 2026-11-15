# Timbre Certo

App Android que pesquisa na internet o timbre de guitarra de uma música e devolve o preset pronto para a pedaleira **M-VAVE Tank-G**, já mandando para ela pelo cabo USB ou pelo Bluetooth.

## Instalar no celular ou tablet

1. Abra a aba **Releases** deste repositório no celular e baixe o `TimbreCerto.apk` mais recente.
2. Toque no arquivo baixado. Se o Android pedir, permita "instalar apps desta fonte" para o navegador.
3. Abra o **Timbre Certo** e vá em **Ajustes**: cole sua chave grátis do Gemini (crie em https://aistudio.google.com/apikey).

## Como usar

- Fale (botão do microfone) ou digite a música e toque em **Gerar timbre**.
- O app pesquisa equipamento e configurações da gravação no Google, mostra as fontes e traduz tudo para os botões da Tank-G e para a sua guitarra.
- **Conectar pelo cabo USB**: cabo USB-C (OTG) ligando a Tank-G ao celular. Feche o app M-EFCS antes.
- **Conectar pelo Bluetooth** (experimental): segure B+C por 3 s na Tank-G e escolha ela na lista.
- **Testar** muda o som na hora sem gravar. **Gravar** salva as partes nos presets A, B e C do banco escolhido em Ajustes.

## Como funciona

- `www/index.html`: toda a interface e a lógica (Gemini com Google Search, painel da Tank-G, codec SysEx).
- `android/app/src/main/java/com/apolozl/timbrecerto/TankMidiPlugin.java`: ponte MIDI nativa (android.media.midi) para USB e Bluetooth LE MIDI.
- O APK é compilado pelo GitHub Actions (`.github/workflows/build-apk.yml`) a cada push na branch `main`.

O protocolo SysEx da Tank-G vem do projeto aberto [tank-g-controller](https://github.com/AlexandreTonin/tank-g-controller) (MIT).

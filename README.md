# MelonBR Mod

Versão modificada do [melonDS Android](https://github.com/rafaelvcaetano/melonDS-android)
(port para Android do emulador de DS [melonDS](https://melonds.kuribo64.net/)), pensada
para quem quer abrir o celular e escolher o jogo, sem cara de emulador.

> **Este app não inclui jogos.** Use as suas próprias cópias. Nenhuma ROM faz parte deste
> repositório nem dos releases.

## O que muda em relação ao melonDS Android

- **Tela "Selecione o jogo"** como tela inicial: um cartão por jogo, com o ícone e o nome
  que vêm na própria ROM.
- **Pasta de jogos pronta**: na primeira abertura o app cria `Documentos/MelonBR Mod/ROMs`,
  mostra o caminho e tem um botão **Abrir pasta**, que abre essa pasta no gerenciador de
  arquivos. Depois de copiar os jogos, toque em **Permitir acesso** (só na primeira vez).
- **Tela de toque com o celular na horizontal**, à escolha (na tela inicial ou no menu de pausa):
  - **Lado a lado**: como no melonDS;
  - **Minitela**: a tela de toque pequena no canto, por cima da tela principal;
  - **Oculta**: só a tela principal. Um botão ⇄ troca as telas quando o jogo pede toque, e um
    aviso aparece quando a tela de toque muda.
- Interface em português.

## Como usar

1. Instale o APK do release (é preciso permitir "instalar apps de fontes desconhecidas").
2. Abra o app e toque em **Abrir pasta**.
3. Copie seus jogos para essa pasta: `.nds`, ou `.zip`/`.7z` com **um jogo por arquivo**.
   Subpastas dentro de `ROMs` também são lidas.
4. Volte ao app, toque em **Permitir acesso** → **Usar esta pasta** → **Permitir**.
5. Toque no jogo. Os saves (`.sav`) ficam ao lado de cada jogo, na mesma pasta.

## Compilar

Requisitos: JDK 21, Android SDK (plataforma 37, build-tools 37), NDK 28.0.13004108 e CMake 3.22.1.

```
git clone --recurse-submodules https://github.com/kyra0077/melonbr-mod.git
cd melonbr-mod
./gradlew assembleGitHubProdRelease
```

O APK sai em `app/build/outputs/apk/gitHubProd/release/`. Sem um keystore em
`local.properties`, a versão release é assinada com a chave de depuração.

### Build pessoal com jogos embutidos (opcional)

Para uso próprio, dá para colocar os seus jogos dentro do APK:
`python tools/pack_android_games.py --list jogos.txt --out <pasta>` e depois
`./gradlew assembleGitHubProdRelease -PembeddedGamesDir=<pasta>`.
Esse APK contém os jogos e **não deve ser distribuído**. A `<pasta>` fica fora do repositório.

## Licença e créditos

GPLv3, como o projeto original (veja [LICENSE](LICENSE)). Baseado no
[melonDS Android](https://github.com/rafaelvcaetano/melonDS-android) de Rafael Caetano e no
[melonDS](https://github.com/melonDS-emu/melonDS) de Arisotura e colaboradores. O README
original está em [README.melonDS-android.md](README.melonDS-android.md).

Pokémon e Nintendo DS são marcas de seus respectivos donos. Este projeto não tem relação com
a Nintendo, a Game Freak ou a The Pokémon Company.

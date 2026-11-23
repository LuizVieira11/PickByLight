# DESIGN.md — WMS Pick-by-Light

Sistema visual do app (Compose Multiplatform, Android + Web/Wasm). Fonte da verdade para cor, tipografia,
ícones e estados. Decidido pelo usuário com apoio do conselho de design (paleta vinho + vermelho vivo).
Capturas do estado anterior: `tools/screenshots/antes/`.

## 1. Contexto e critério

- **Quem usa:** operador de armazém, celular na mão, às vezes de luva, sob luz forte. Olha a tela de relance,
  entre a prateleira e o próximo passo.
- **Critério de leitura:** em menos de 1 s o operador sabe **para onde ir**, **se acertou** e **se errou**.
- **Quem mais avalia:** a banca, vendo o app no celular e na Web.
- **Princípio central:** o vermelho tem dois papéis e eles nunca se misturam. **Vinho é identidade.**
  **Vermelho vivo é alarme.** Nenhum estado depende só de cor: sempre cor + ícone + texto (+ vibração no erro).
- **Tema:** claro, fixo (luz forte). Não há tema escuro nem cor dinâmica (Material You).
  Com o celular em modo escuro, o `enableEdgeToEdge()` padrão pintaria os ícones da barra de status de branco
  sobre o fundo claro (invisíveis): a barra de status e a de navegação usam `SystemBarStyle.light(...)` fixo.

## 2. Cores reservadas

Contrastes medidos pela fórmula WCAG 2.x. Texto exige 4,5:1; contorno de componente exige 3:1.

| Token (`Color.kt`) | Hex | Pode | Não pode | Contraste |
|---|---|---|---|---|
| `BrandWine` | `#8B1E2D` | Barra superior, logo, indicador do item de navegação selecionado, títulos de seção | Botões, ações clicáveis, fundo de conteúdo, qualquer estado | 9,05:1 c/ branco; 8,19:1 sobre `Paper` |
| `AlarmRed` | `#D7261E` | Alerta Poka-Yoke (tela cheia) e erros que **bloqueiam** uma ação (`CONFIG_INVALID`, falha de gravação) | Marca, botões comuns, status informativo (ex.: ESP OFFLINE) | 5,02:1 c/ branco; 4,54:1 sobre `Paper` |
| `OkTeal` | `#0E7A6D` | Sucesso, confirmação, ESP ONLINE, NFC vinculado | Ação, decoração | 5,22:1 c/ branco; 4,72:1 sobre `Paper` |
| `WarnAmber` | `#9A5B00` | Aviso, modo manual, broker desconectado, ESP STALE, NFC pendente | Erro, sucesso | 4,91:1 sobre `Paper`; 5,43:1 sobre branco |
| `ActionGraphite` | `#2B2724` | Botão primário (uma ação principal por tela) | Decoração | 14,81:1 c/ branco |
| `Ink` | `#1F1B18` | Texto principal | — | 15,48:1 sobre `Paper` |
| `InkMuted` | `#5E5650` | Texto secundário, ESP OFFLINE, desabilitado | Texto principal | 6,50:1 sobre `Paper` |
| `Paper` | `#F7F3EE` | Fundo do app | — | — |
| `SurfaceCard` | `#FFFFFF` | Fundo de card e de campo | — | — |
| `SurfaceMuted` | `#EDE7DF` | Contêiner discreto, chip neutro, botão desabilitado | — | `Ink` 13,92:1; `InkMuted` 5,85:1 |
| `LineOutline` | `#8F857B` | Bordas de campos e cards, divisórias | Texto | 3,27:1 sobre `Paper` |
| `LedAmber` | `#F2A900` | Ponto "LED aceso" na Separação | Qualquer outro uso | 7,37:1 sobre grafite |
| `OnStrong` | `#FFFFFF` | Texto/ícone sobre `BrandWine`, `AlarmRed`, `OkTeal`, `ActionGraphite` | — | ver acima |

**Texto em cor de estado** (`OkTeal`, `WarnAmber`, `AlarmRed`) só sobre `Paper` ou `SurfaceCard`, **nunca sobre
`SurfaceMuted`** (medido: 4,25 / 4,42 / 4,08:1, abaixo do AA). Chip ou faixa de estado = fundo `SurfaceCard` +
borda 1 dp na cor do estado + texto/ícone na cor do estado, **ou** fundo cheio na cor do estado + `OnStrong`.

**Mapeamento Material 3 (`Theme.kt`, só `lightColorScheme`):** `primary = ActionGraphite`, `onPrimary = OnStrong`,
`secondary = BrandWine`, `onSecondary = OnStrong`, `tertiary = OkTeal`, `onTertiary = OnStrong`,
`background = Paper`, `onBackground = Ink`, `surface = SurfaceCard`, `onSurface = Ink`,
`surfaceVariant = SurfaceMuted`, `onSurfaceVariant = InkMuted`, `outline = LineOutline`,
`error = AlarmRed`, `onError = OnStrong`, `errorContainer = AlarmRed`, `onErrorContainer = OnStrong`.
O `error` é definido **à mão**: nunca gerar a paleta a partir de uma cor-semente vermelha.

**Todos os demais papéis também são definidos** (um papel omitido cai no padrão lilás do Material 3; visto
no "depois" da D1 em chip selecionado e no botão "+" da Separação):
`primaryContainer = SurfaceMuted`, `onPrimaryContainer = Ink`, `secondaryContainer = SurfaceMuted`,
`onSecondaryContainer = Ink`, `tertiaryContainer = SurfaceMuted`, `onTertiaryContainer = Ink`,
`surfaceTint = SurfaceCard` (anula o tingimento por elevação), `surfaceBright = SurfaceCard`,
`surfaceDim = SurfaceMuted`, `surfaceContainerLowest/Low/Container = SurfaceCard`,
`surfaceContainerHigh = SurfaceCard` (é o fundo do `AlertDialog`: diálogo branco, erro legível),
`surfaceContainerHighest = SurfaceMuted`, `outlineVariant = SurfaceMuted`, `inverseSurface = Ink`,
`inverseOnSurface = Paper`, `inversePrimary = Paper`.

**Saem:** toda a paleta Tailwind atual (`0F172A`, `1E293B`, `334155`, `059669`, `DC2626`, `F59E0B`, `1D4ED8`,
`EA580C` e variantes) e as cores por nível de endereço (ver seção 6).

## 3. Tipografia

- **Uma família:** Barlow (licença OFL, Google Fonts), pesos **400** e **600**, empacotada em
  `composeResources/font` (vale para Android e Web). Escolhida pelo usuário: levemente estreita e com cara de
  sinalização industrial, o que cabe mais texto por linha em tela fina. Nada de `FontFamily.Default`.
- **Números tabulares** (`fontFeatureSettings = "tnum"`; a Barlow tem `tnum`, conferido no arquivo da fonte)
  em endereços, quantidades, saldos e IDs.
- **Escala (sp):** 40 (endereço-herói da Separação, 600) · 28 (título de tela, 600) · 22 (título de seção, 600)
  · 18 (destaque, 600) · 16 (corpo, 400; **mínimo para qualquer texto**, conforme a spec).
- **Texto de interface:** frase com só a primeira letra maiúscula; sem rótulo pequeno em caixa-alta acima de
  título ("BROKER MQTT" vira "Broker MQTT"); verbo ativo nos botões; erro diz o que aconteceu e o que fazer.
- **Rótulo de ação nunca é cortado** com "…": encurte o texto ("Separação" em vez de
  "Separação de Pedidos (Pick-by-Light)").

## 4. Forma, espaçamento e layout

- **Espaçamento:** 4 · 8 · 12 · 16 · 24 · 32 dp. Respiro maior entre grupos do que dentro do grupo.
- **Margens (largura útil):** a tela é fina; a margem não pode comer o conteúdo.
  - **Causa medida no "antes":** `App.kt` usa `safeContentPadding()`, que soma a área do gesto de voltar
    (106 px = 37,7 dp de cada lado no Edge 60 Pro, conferido via `adb dumpsys window`) aos 12 dp da tela: o card começa a **50 dp** da borda e o texto da Separação a ~63 dp,
    num aparelho de 434 dp de largura. Na árvore de Endereços (3 cards aninhados) o texto da posição começa
    a ~114 dp. Correção: `safeDrawingPadding()` (evita barras e recortes, não a área de gesto).
  - **Margem lateral da tela:** 16 dp, um valor só, vindo de `Dimens` (`screenHorizontalPadding`).
    Nenhuma tela soma padding horizontal próprio por cima dele.
  - **Padding interno do card:** 16 dp. Texto fica a 32 dp da borda do aparelho.
  - **Hierarquia não gasta largura:** um nível de card no máximo; o recuo de cada nível da árvore é 16 dp,
    feito dentro do mesmo card, nunca com card dentro de card.
  - **Listas usam a largura toda:** separadores de 1 dp entre itens em vez de um card por item quando o item
    só tem texto e uma ação.
  - **Medida de aceite:** no Motorola Edge 60 Pro (434 dp), o texto do corpo começa a ≤ 32 dp da borda em
    todas as telas. Na árvore de Endereços o recuo vale para o ícone do nível (32/48/64 dp) e o texto vem
    32 dp depois dele: texto da posição a ≤ 96 dp (era ~114 dp com cards aninhados).
- **Cantos:** 8 dp em cards, campos e botões. Sem sombra; card = `SurfaceCard` + borda 1 dp `LineOutline`.
- **Toque:** mínimo 48 dp; botão primário com altura mínima de 56 dp (spec).
- **Proibido:** card dentro de card (no máximo um nível; hierarquia por recuo + ícone + divisória);
  faixa colorida na lateral do card; grade de cards de métrica com risco colorido; botões em cores diferentes
  para navegação ("arco-íris"); gradiente, brilho neon, glassmorphism.
- **Botões:** primário `ActionGraphite` (uma ação principal por tela); secundário contornado (`LineOutline`,
  texto `Ink`); terciário só texto. Cor nunca indica para onde o botão leva.

## 5. Ícones

- **Fonte:** Material Symbols (estilo Rounded, peso 400, 24 dp), copiados como vetores para
  `composeResources/drawable` — **só os usados**. Não usar `material-icons-extended`.
- **Todo `Icon` tem `contentDescription`**; ícone puramente decorativo usa `null` com comentário de uma linha.
- **Zero emojis na UI.** Substituições:

| Emoji | Símbolo | Emoji | Símbolo | Emoji | Símbolo |
|---|---|---|---|---|---|
| ⚠️ | `warning` | ➕ | `add` | 🏷️ | `nfc` |
| 📦 | `inventory_2` | 📍 | `location_on` | ⚙️ | `settings` |
| ✅ | `check_circle` | 💡 | `lightbulb` | 🔒 | `lock` |
| 📋 | `list_alt` | ⏱️ | `timer` | 🎯 | `my_location` |
| 🧭 | `route` | 👤 | `person` | ❌ | `cancel` |
| 📅 | `calendar_today` | ✏️ | `edit` | 📡 | `sensors` |
| 📤 | `file_export` | 🔍 | `search` | 🕒 | `schedule` |
| ⇄ | `swap_horiz` | 🎉 | `check_circle` (sem celebração) | 🟢 | ponto `LedAmber` (seção 6) |
| ▲ / ▼ (expandir/ocultar) | `expand_less` / `expand_more` | | | | |
| → / ↔ (em `CH01 → GPIO 4`) | tabela de duas colunas, sem seta | | | | |

**Arquivos de ícone:** baixados de
`https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/<nome>/materialsymbolsrounded/<nome>_24px.xml`
(os 31 nomes acima foram conferidos: HTTP 200), salvos como `composeResources/drawable/ic_<nome>.xml`.
Normalizar cada XML: **remover** a linha `android:tint="?attr/colorControlNormal"` e trocar
`@android:color/white` por `#FF000000` (o Compose Multiplatform não resolve referências do Android; a cor
final vem do `tint` do `Icon`).

## 6. Estados (cor + forma + texto)

| Estado | Visual | Texto (exemplo) | Extra |
|---|---|---|---|
| **Tag errada (Poka-Yoke)** | Tela cheia `AlarmRed`, ícone `dangerous` (octógono) 96 dp, borda listrada diagonal | "Tag errada. Devolva ao endereço E2-P3-04." | Vibração longa (600 ms) já existente |
| **Erro que bloqueia ação** | Ícone `error` + texto em `AlarmRed` | "Não foi possível gravar. O ESP está offline." | — |
| **Sucesso** | Ícone `check_circle` + `OkTeal` | "Item confirmado." | Bipe de sucesso existente |
| **Aviso / modo manual** | Ícone `warning` + `WarnAmber` | "Modo manual: confirme pela leitura NFC." | — |
| **Broker desconectado** | Faixa `WarnAmber` com `cloud_off` | "Sem conexão com o broker. Tentando de novo…" | Substitui o banner com ⚠️ |
| **LED aceso** | Ponto `LedAmber` 24 dp com anel de 2 dp + texto | "LED aceso em E2-P3-04" | A tela não promete a cor física do LED |
| **ESP ONLINE / OFFLINE / Possivelmente antigo / CONFIG_INVALID** | Chip `OkTeal`+`wifi` / `InkMuted`+`cloud_off` / `WarnAmber`+`history` / `AlarmRed`+`error` | nome do estado por extenso | OFFLINE **não** é vermelho |
| **Níveis de endereço** | Sem cor por nível: ícone (`shelves` estante, `table_rows` prateleira, `inventory_2` posição) + recuo de 16 dp + rótulo | "Estante EST-A", "Prateleira PRAT-A1", "Posição POS-01" | Aprovado; `ESPECIFICACAO.md` seção 3.2 já ajustada |

## 7. Regras verificáveis (o revisor roda antes de aceitar cada etapa)

1. Nenhum `Color(0x` fora de `ui/theme/Color.kt`.
2. Nenhum emoji em `app/shared/src/commonMain` (busca pelo intervalo Unicode de emojis = 0).
3. Nenhum `FontFamily.Default` em `ui/theme/Type.kt`.
4. Todo `Icon(` tem `contentDescription` (literal ou `null` comentado).
5. `isSystemInDarkTheme`, `darkColorScheme` e `dynamic*ColorScheme` ausentes de `ui/theme`.
6. `AlarmRed` só aparece via `MaterialTheme.colorScheme.error*` ou nos componentes de erro/Poka-Yoke.
7. Nenhum `maxLines = 1` com `TextOverflow.Ellipsis` em rótulo de botão.
8. Nenhum `safeContentPadding` no app; padding horizontal de nível de tela só via `Dimens`.
9. Todos os papéis de cor da seção 2 aparecem no `lightColorScheme(...)` do `Theme.kt`.
10. Nenhum texto/ícone em `OkTeal`, `WarnAmber` ou `colorScheme.error` dentro de container `SurfaceMuted`
    (`surfaceVariant`, `*Container`, `surfaceContainerHigh*`).

## 8. Etapas e aceite

| Etapa | Escopo | Aceite |
|---|---|---|
| **D1** | `Color.kt`, `Theme.kt` (só claro), `Type.kt` + fonte Barlow, `Dimens.kt` (margem 16 dp) e `safeDrawingPadding()` no `App.kt` | Regras 1, 3, 5 e 8; testes, `compileKotlinWasmJs` e `assembleDebug` verdes; prints do Dashboard e da Separação com o card a 16 dp da borda |
| **D2** | 53 emojis → Material Symbols | Regras 2 e 4; prints das 7 telas |
| **D3** | Tela a tela, começando pela Separação (endereço-herói, estados da seção 6), depois as demais; níveis de endereço sem cor | Regras 6 e 7; contraste AA de cada par usado; prints antes/depois aprovados pelo usuário, inclusive sob luz forte e com simulação de daltonismo (protanopia/deuteranopia) |
| **Final** | Passada pelo checklist anti-clichê (frontend-design da Anthropic) | Sem achados abertos |

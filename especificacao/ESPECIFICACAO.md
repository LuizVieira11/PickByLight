# Especificação Inicial e Técnica - App Mobile/Web WMS (Pick-by-Light)

**Objetivo:** Desenvolver a interface visual (UI), os fluxos lógicos (UX) e a camada de persistência e comunicação local do aplicativo WMS (Warehouse Management System). Nesta **Fase 1**, o sistema operará **sem** dependência de um backend relacional externo (API REST/PostgreSQL), utilizando armazenamento local (Room Database / Mock em memória) com dados pré-carregados (*seed*). A comunicação externa será focada no disparo e monitoramento de requisições **MQTT** diretamente para a camada de hardware (ESP32 e fitas/módulos de LED) e leitura física via **NFC nativo**.

---

## 1. Arquitetura, Tecnologias e UX/UI Responsivo (Fase 1)

### 1.1. Stack Tecnológica e Padrões Arquiteturais
* **Linguagem e Framework:** Kotlin utilizando **Jetpack Compose / Compose Multiplatform (CMP)** sob arquitetura **MVVM (Model-View-ViewModel)** com padrão *Repository* e *Unidirectional Data Flow (UDF)*.
* **Responsividade (Crucial):** O design deve ser *mobile-first*, focado na ergonomia de quem segura o celular ou coletor de dados com apenas uma mão, mas **obrigatoriamente responsivo** para tablets industriais e monitores.
* **Versão Web:** A estrutura em Compose Multiplatform deve manter a camada de UI e regras de negócio desacopladas (`commonMain`), preparando o projeto para compilação Web (Kotlin/Wasm ou Kotlin/JS) para uso em estações de trabalho administrativas.
* **Armazenamento de Estado:** Banco de dados local **Room (SQLite)** ou repositório em memória com `StateFlow` para simular o estoque, movimentações e a hierarquia de endereços.
* **Comunicação IoT:** Cliente MQTT integrado (Eclipse Paho MQTT / HiveMQ) operando como *Singleton* reativo.

### 1.2. Breakpoints de Responsividade
O layout deve reagir dinamicamente às classes de tamanho de janela (*WindowSizeClass*):

| Classe de Tamanho | Largura de Tela | Dispositivo Alvo | Padrão de Navegação e Layout |
| :--- | :--- | :--- | :--- |
| **`compact`** | $< 600\text{ dp}$ | Smartphones e Coletores Mobile | *Bottom Navigation Bar*, layout em coluna única, botões com altura $\ge 56\text{ dp}$. |
| **`medium`** | $600\text{ dp} \text{ a } 840\text{ dp}$ | Tablets Pequenos / Dobráveis | *Navigation Rail* lateral esquerda, layout em 2 colunas (lista-detalhe). |
| **`expanded`** | $> 840\text{ dp}$ | Tablets Industriais e Desktop/Web | *Permanent Side Navigation Drawer* fixa + Dashboard e catálogos em *Grid* adaptativo. |

### 1.3. Diretrizes de Acessibilidade Industrial e Estados de Tela
Para operação em galpões logísticos e almoxarifados com condições variáveis:
* **Alto Contraste:** Paleta de cores semânticas fortes (Verde Esmeralda para sucesso/validação, Vermelho Coral para erro/divergência, Amarelo Âmbar para alertas/pendências) garantindo legibilidade sob luz intensa ou baixa luminosidade.
* **Ergonomia para Uso com Luvas (EPI):** Todos os elementos clicáveis devem ter área de toque mínima de $48\text{ dp} \times 48\text{ dp}$ (sendo o padrão ideal $\ge 56\text{ dp}$ de altura para botões de ação primária) e espaçamento mínimo de $12\text{ dp}$ entre botões.
* **Feedback Multimodal (Tátil e Sonoro):**
  * **Sucesso (Bipagem correta):** 1 vibração curta ($150\text{ ms}$) + sinal sonoro agudo (*beep* curto de confirmação).
  * **Alerta/Aviso:** 2 vibrações médias ($250\text{ ms}$) + tom sonoro duplo.
  * **Erro Crítico (Local incorreto / Tag inválida):** 1 vibração longa e intensa ($600\text{ ms}$) + sinal sonoro grave de falha.
* **Tipografia Escalável:** Textos de leitura operacional com tamanho mínimo de $16\text{ sp}$ (títulos e códigos de endereço em $20\text{ sp}$ a $28\text{ sp}$ em negrito), com suporte a opção de "Fonte Ampliada" na **Tela de Ajustes e Configurações (Seção 3.6)**.
* **Estados de Tela Padronizados:** Todos os fluxos e telas devem implementar explicitamente os 4 estados visuais no `UiState`:
  1. `Loading`: Indicador de progresso circular/linear com mensagem contextual (ex.: *"Aguardando confirmação do hardware ESP32..."*).
  2. `Empty`: Ilustração/ícone vetorial claro quando listas ou buscas não retornarem dados, acompanhado de botão de ação (ex.: *"[Cadastrar Primeiro Item]"*).
  3. `Error`: Estado visual de falha com ícone de alerta, explicação amigável do problema e botão de recuperação (ex.: *"[Tentar Novamente]"*).
  4. `Success`: Transição visual fluida (cor verde + ícone de *check* animado) confirmando a operação.

### 1.4. Identificação via NFC Nativo do Celular
Para emular a experiência industrial do RFID sem depender de leitores externos acoplados na Fase 1, o aplicativo utilizará o **leitor NFC nativo do Android** (`NfcAdapter`).
* **Padrão Físico e Formato de UID:** Etiquetas NFC passivas padrão **NTAG213** (ou Mifare Ultralight) coladas fisicamente nas posições da maquete. O aplicativo deve ler e normalizar o identificador único (**UID de 7 bytes** em formato hexadecimal maiúsculo separado por dois-pontos, ex.: `04:A1:2B:3C:4D:5E:6F`).
* **Captura de Intenção:** Implementação via `enableForegroundDispatch` ou `enableReaderMode` na `MainActivity`, interceptando tags (`TAG_DISCOVERED`, `NDEF_DISCOVERED`, `TECH_DISCOVERED`) e convertendo `tag.id` (ByteArray) para String Hexadecimal.
* **Regras de Validação de Leitura NFC:**
  * **Debounce de Leitura Duplicada:** Leituras consecutivas do mesmo UID em um intervalo $< 2\text{ segundos}$ devem ser ignoradas silenciosamente para evitar disparos múltiplos acidentais.
  * **Tag já vinculada (Durante o Cadastro):** Se o operador tentar vincular uma tag que já pertence a outro endereço, bloquear a ação e exibir erro: *"Tag já cadastrada em [Estante X > Prat Y > Pos Z]"*.
  * **Tag não cadastrada (Durante Operações):** Se o operador bipar uma tag desconhecida no Recebimento ou Separação, exibir alerta amarelo/vermelho: *"Tag não cadastrada! Cadastre a posição primeiro"* com atalho direto para o cadastro.
  * **Simulador de NFC (Fallback para Telas sem NFC / Web / Emulador):** Em modo de desenvolvimento (habilitado via toggle "Modo Debug NFC" na **Tela de Ajustes — Seção 3.6**) ou telas `expanded`, exibir um botão flutuante discreto `[Simular Leitura NFC]` que permite escolher qual UID do *seed* (ou UID desconhecido) disparar para fins de teste.

---

## 2. Estrutura de Dados Local (Mock / Room Database)

### 2.1. Dicionário de Entidades
> **Observação Geral sobre Geração de IDs:** Todos os identificadores primários (`id`) das entidades `Estante`, `Prateleira`, `Posicao`, `Item` e `Movimentacao` são do tipo `String` gerados na camada de aplicação por `UUID.randomUUID().toString()` (padrão UUID v4 em string lowercase), garantindo consistência e permitindo futura sincronização offline-first sem colisão.

O banco de dados local (Room) e o repositório em memória devem implementar as 6 entidades abaixo:

| Entidade | Campos Principais | Regras e Relacionamentos |
| :--- | :--- | :--- |
| **`Estante`** | `id` (PK), `codigo` (Unique), `nome`, `led_id` (Unique), `setor` | Nível raiz da hierarquia física. `led_id` aciona o sinalizador superior (estilo estacionamento). `id` gerado via UUID v4. |
| **`Prateleira`** | `id` (PK), `estante_id` (FK), `codigo` (Unique composto com `estante_id`), `led_id_ponta` (Unique) | Pertence a uma `Estante`. `led_id_ponta` aciona o LED indicador na borda da prateleira. Índice único composto: `(estante_id, codigo)`. O mesmo código pode existir em pais diferentes. `id` gerado via UUID v4. |
| **`Posicao`** | `id` (PK), `prateleira_id` (FK), `codigo` (Unique composto com `prateleira_id`), `limites_lap`, `led_id` (Unique) | Subdivisão final da `Prateleira`. Índice único composto: `(prateleira_id, codigo)`. O mesmo código pode existir em pais diferentes. `limites_lap`: dimensões físicas no formato `LxA x P` seguido de unidade (`cm` ou `mm`). Exemplos válidos: `30x20x40cm`, `150x80x200mm`. Validação por regex (ver Seção 2.2). O vínculo NFC **não** é armazenado nesta entidade; é resolvido exclusivamente via tabela `TagNFC` (fonte única de verdade). O status 'vinculada' é derivado por JOIN/consulta. `id` gerado via UUID v4. |
| **`TagNFC`** | `uid` (PK - Hex 7 bytes), `posicao_id` (FK Unique), `data_vinculo` | Mapeamento rápido (`UID <-> Posicao`) para resolução em $O(1)$ após leitura física. **Fonte única de verdade** do vínculo UID $\leftrightarrow$ Posição. Nenhum outro campo `tagUid` deve existir em outras entidades para evitar duplicação. |
| **`Item`** | `id` (PK), `sku` (Unique), `nome`, `categoria`, `unidade`, `estoque_minimo`, `quantidade_estoque`, `posicao_id` (FK Nullable), `em_separacao` (Boolean) | Produto do catálogo. Contém flag `em_separacao` para controle de *lock* otimista local. `id` gerado via UUID v4. |
| **`Movimentacao`** | `id` (PK), `tipo` (`ENTRADA` / `SAIDA` / `TRANSFERENCIA`), `item_id` (FK), `posicao_id` (FK), `quantidade`, `observacao`, `timestamp`, `usuario`, `modo_manual` (Boolean) | Histórico imutável de auditoria para rastreabilidade das operações na maquete. `id` gerado por `UUID.randomUUID().toString()` na camada de aplicação (padrão UUID v4 em string lowercase). Alternativamente, `@PrimaryKey(autoGenerate = true)` do Room, mas UUID é preferível para permitir futura sincronização offline-first sem colisão. Campo `modo_manual` marca operações realizadas em modo degradado (LED indisponível), útil para relatórios de confiabilidade. |

### 2.2. Estruturas de Dados em Kotlin (`data class`)

```kotlin
package com.uniso.wms.pickbylight.domain.model

import java.util.UUID

enum class TipoMovimentacao { ENTRADA, SAIDA, TRANSFERENCIA }

data class Estante(
    val id: String = UUID.randomUUID().toString(), // UUID v4
    val codigo: String,
    val nome: String,
    val ledId: String,
    val setor: String = "Geral"
)

// Room: @Entity(indices = [Index(value = ["estanteId", "codigo"], unique = true)])
data class Prateleira(
    val id: String = UUID.randomUUID().toString(), // UUID v4
    val estanteId: String,
    val codigo: String,
    val ledIdPonta: String
)

// Room: @Entity(indices = [Index(value = ["prateleiraId", "codigo"], unique = true)])
data class Posicao(
    val id: String = UUID.randomUUID().toString(), // UUID v4
    val prateleiraId: String,
    val codigo: String,
    val limitesLap: String = "30x20x40cm", // Regex: ^\d{1,4}x\d{1,4}x\d{1,4}(cm|mm)$
    val ledId: String
    // tagUid removido: vínculo resolvido via TagNFC (fonte única de verdade)
)

data class TagNFC(
    val uid: String, // Formato esperado: "04:A1:2B:3C:4D:5E:01" (7 bytes hex)
    val posicaoId: String,
    val dataVinculo: Long
)

data class Item(
    val id: String = UUID.randomUUID().toString(), // UUID v4
    val sku: String,
    val nome: String,
    val categoria: String,
    val unidade: String, // Ex: "UN", "CX", "PAR", "MT"
    val estoqueMinimo: Int = 5,
    val quantidadeEstoque: Int = 0,
    val posicaoId: String? = null,
    val emSeparacao: Boolean = false // Lock otimista para evitar retirada simultânea
)

data class Movimentacao(
    val id: String = java.util.UUID.randomUUID().toString(), // UUID v4
    val tipo: TipoMovimentacao,
    val itemId: String,
    val posicaoId: String,
    val quantidade: Int,
    val observacao: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val usuario: String = "Operador Padrão",
    val modoManual: Boolean = false // true quando operação ocorreu sem sinal luminoso
)
```

### 2.3. Dados de Seed (Mock Inicial para Apresentação)
Ao inicializar o aplicativo pela primeira vez, o sistema deve popular automaticamente o banco local com **2 estantes, 4 prateleiras, 8 posições, 6 vínculos NFC e 5 itens**.
*(Nota: O seed respeita unicidade composta. Códigos podem se repetir entre pais, ex.: `POS-01` em `PRAT-A1` e em `PRAT-B1`).*

* **Estantes (2):**
  1. `id: "EST_A"`, `codigo: "EST-A"`, `nome: "Estante A - Eletrônica"`, `ledId: "EST_A_LED1"`
  2. `id: "EST_B"`, `codigo: "EST-B"`, `nome: "Estante B - Mecânica"`, `ledId: "EST_B_LED1"`
* **Prateleiras (4):**
  1. `id: "PRAT_A1"`, `estanteId: "EST_A"`, `codigo: "PRAT-A1"`, `ledIdPonta: "PRAT_A1_LED1"`
  2. `id: "PRAT_A2"`, `estanteId: "EST_A"`, `codigo: "PRAT-A2"`, `ledIdPonta: "PRAT_A2_LED1"`
  3. `id: "PRAT_B1"`, `estanteId: "EST_B"`, `codigo: "PRAT-B1"`, `ledIdPonta: "PRAT_B1_LED1"`
  4. `id: "PRAT_B2"`, `estanteId: "EST_B"`, `codigo: "PRAT-B2"`, `ledIdPonta: "PRAT_B2_LED1"`
* **Posições (8 — sendo 6 com vínculo na tabela `TagNFC` e 2 pendentes para demonstração de cadastro ao vivo):**
  1. `POS_A1_01` (`PRAT_A1`) | LED: `POS_A1_01` | (vínculo registrado na tabela `TagNFC`)
  2. `POS_A1_02` (`PRAT_A1`) | LED: `POS_A1_02` | (vínculo registrado na tabela `TagNFC`)
  3. `POS_A2_01` (`PRAT_A2`) | LED: `POS_A2_01` | (vínculo registrado na tabela `TagNFC`)
  4. `POS_A2_05` (`PRAT_A2`) | LED: `POS_A2_05` | (vínculo registrado na tabela `TagNFC`)
  5. `POS_B1_01` (`PRAT_B1`) | LED: `POS_B1_01` | (vínculo registrado na tabela `TagNFC`)
  6. `POS_B1_02` (`PRAT_B1`) | LED: `POS_B1_02` | (vínculo registrado na tabela `TagNFC`)
  7. `POS_B2_01` (`PRAT_B2`) | LED: `POS_B2_01` | Sem registro na tabela `TagNFC` (pendente de vinculação)
  8. `POS_B2_02` (`PRAT_B2`) | LED: `POS_B2_02` | Sem registro na tabela `TagNFC` (pendente de vinculação)
* **Vínculos NFC (tabela TagNFC — 6 registros iniciais):**
  1. `uid: "04:A1:2B:3C:4D:5E:01"` | `posicaoId: "POS_A1_01"`
  2. `uid: "04:A1:2B:3C:4D:5E:02"` | `posicaoId: "POS_A1_02"`
  3. `uid: "04:A1:2B:3C:4D:5E:03"` | `posicaoId: "POS_A2_01"`
  4. `uid: "04:A1:2B:3C:4D:5E:04"` | `posicaoId: "POS_A2_05"`
  5. `uid: "04:A1:2B:3C:4D:5E:05"` | `posicaoId: "POS_B1_01"`
  6. `uid: "04:A1:2B:3C:4D:5E:06"` | `posicaoId: "POS_B1_02"`
* **Itens (5):**
  1. `SKU: "ARD-ESP32"` | Nome: `"Microcontrolador ESP32 Wi-Fi"` | Cat: `"Microcontroladores"` | Un: `"UN"` | Estoque: `25` | Posição: `POS_A1_01`
  2. `SKU: "MOD-RC522"` | Nome: `"Módulo Leitor RFID RC522"` | Cat: `"Sensores"` | Un: `"UN"` | Estoque: `18` | Posição: `POS_A1_02`
  3. `SKU: "LED-WS2812"` | Nome: `"Fita de LED Endereçável 5m"` | Cat: `"Iluminação"` | Un: `"UN"` | Estoque: `12` | Posição: `POS_A2_05`
  4. `SKU: "TAG-NTAG213"` | Nome: `"Kit Tags NFC NTAG213 Adesivas"` | Cat: `"Identificação"` | Un: `"CX"` | Estoque: `40` | Posição: `POS_A2_01`
  5. `SKU: "SRV-MTR01"` | Nome: `"Servo Motor Industrial 20kg"` | Cat: `"Atuadores"` | Un: `"UN"` | Estoque: `8` | Posição: `POS_B1_01`

---

## 3. Mapa de Telas e Fluxos de Navegação (UI/UX)

### 3.1. Tela Inicial (Dashboard)
* **Cabeçalho de Status IoT e Ações Superiores:**
  * Indicador em tempo real da conexão com o Broker MQTT (`Conectado` em verde, `Reconectando...` em amarelo ou `Offline` em vermelho).
  * Botão rápido **`[Ping ESP32]`**.
  * Botão **`[Histórico]`** (ícone de lista na barra superior, ao lado da engrenagem) que abre a **Tela de Histórico de Movimentações (Seção 3.8)**.
  * Ícone de engrenagem **⚙️** no canto superior direito que abre a **Tela de Ajustes (Seção 3.6)**.
* **Resumo Rápido (Cards):** Total de Itens em Estoque, Movimentações Hoje (Entradas/Saídas), Posições Mapeadas (ex.: `6/8 vinculadas ao NFC`) e Alertas de Estoque Baixo.
* **Menu de Ações Principais:** Botões amplos ($\ge 56\text{ dp}$), com ícones claros:
  * **[Recebimento de Itens]**
  * **[Separação de Pedidos (Pick-by-Light)]**
  * **[Gestão de Endereços]**
  * **[Catálogo de Itens]**

### 3.2. Módulo de Gestão de Endereços (Armazenagem e Vinculação NFC)
Fluxo em cascata (*Estante -> Prateleira -> Posição*) para mapear o armazém físico e atrelar hardware luminoso e tags NFC.

* **Tela de Listagem de Endereços:**
  * Barra de busca no topo (filtra por código de estante, prateleira, posição ou UID da tag).
  * Filtro rápido por chips: `[Todos]`, `[NFC Vinculado]`, `[NFC Pendente]`.
  * Visão em árvore expansível (*Accordion*):
    * **Nível 1 (Azul): Estante** — Exibe nome, código, ID do LED e botão `[+ Prateleira]`.
    * **Nível 2 (Vermelho/Salmão): Prateleira** — Exibe código, ID do LED de ponta, botão `[Testar LED]` e botão `[+ Posição]`.
    * **Nível 3 (Verde): Posição** — Exibe código, limites físicos, ID da Fita de LED, **Badge de status NFC (consulta via JOIN com `TagNFC`): `Vinculado: 04:A1...` em verde ou `Pendente NFC` em amarelo** e botão `[Testar LED]`.
* **Fluxo de Cadastro em Cascata:**
  1. **Tela de Cadastro de Estante:** Campos: Nome/Código da Estante (obrigatório, único), Descrição/Setor, ID do LED da Estante (sinalizador superior). Botão `[Salvar Estante]`.
  2. **Tela de Cadastro de Prateleira:** Contexto da Estante selecionada. Campos: Código da Prateleira (obrigatório, único dentro da estante), ID do LED de Ponta (obrigatório, único). Botões: `[Testar LED]` (dispara MQTT rápido de teste `TESTE_LED` — ver Seção 4.3) e `[Salvar Prateleira]`.
  3. **Tela de Cadastro de Posição (Vinculação NFC):** Contexto da Prateleira selecionada.
     * **Código da Posição:** Obrigatório, único dentro da prateleira.
     * **Limites Físicos (L×A×P):** Campo de texto com máscara e validação regex `^\d{1,4}x\d{1,4}x\d{1,4}(cm|mm)$` (ex.: `30x20x40cm`). Exibir erro inline se inválido: *"Formato esperado: 30x20x40cm ou 150x80x200mm"*.
     * **ID da Fita de LED Posicional:** Obrigatório, único. Botão `[Testar LED]` (dispara MQTT `TESTE_LED` — ver Seção 4.3).
     * **Ação Chave — `[Escanear Etiqueta NFC]`:** Abre um *BottomSheet* ou Modal com animação de ondas de rádio: *"Aproxime a traseira do celular da etiqueta fixa na prateleira"*.
     * Ao detectar a tag: valida o formato hexadecimal de 7 bytes, aplica debounce de $2\text{ s}$ e verifica unicidade. A vinculação deve ser gravada **exclusivamente** como um novo registro na tabela `TagNFC` (nunca em `Posicao`). Garantir transação atômica (`@Transaction` no Room):
       * Se válida e livre: vibra curto, exibe o UID lido em destaque verde e habilita `[Concluir Cadastro]`.
       * Se já estiver em uso: vibra longo e exibe erro *"Tag já cadastrada em [Estante X > Prat Y > Pos Z]"*.

### 3.3. Módulo de Catálogo de Itens
Acessado pelo botão **[Catálogo de Itens]** no Dashboard, permitindo gestão completa dos materiais armazenados.

* **Tela de Listagem do Catálogo:**
  * Campo de busca instantânea por **SKU** ou **Nome do Item**, além de filtro por Categoria e alerta de *"Abaixo do Estoque Mínimo"*.
  * Cada card de item na lista exibe:
    * Nome do produto e badge com `SKU`.
    * Categoria e Unidade de medida (`UN`, `CX`, etc.).
    * **Localização Atual Completa:** Ex.: `Estante A > Prat A1 > Pos A1-01` (ou badge `"Sem Endereço"` caso não alocado).
    * **Saldo em Estoque:** Quantidade atual em destaque (fica vermelha se `< estoque_minimo`).
    * **Ações Rápidas no Card:** Botão `[Editar]` e botão **`[Mover Item]`**.
* **Tela/Modal de Cadastro e Edição de Item:**
  * Campos: `SKU` (obrigatório, único, com botão opcional para ler código de barras), `Nome` (obrigatório), `Categoria` (dropdown/chips), `Unidade` (`UN`, `CX`, `PAR`, `MT`), `Estoque Mínimo` (numérico, padrão 5).
  * Botão `[Salvar Item]`.
* **Ação Rápida — `[Mover Item]` (Transferência Interna):**
  * Ao clicar em `[Mover Item]`, abre um modal exibindo a posição de origem atual e solicitando a **Posição de Destino** (pode ser selecionada em dropdown hierárquico ou bipando a Tag NFC da nova prateleira) e a quantidade a mover.
  * Ao confirmar, atualiza o `posicao_id` do item, registra um log na tabela `Movimentacao` com `tipo = TRANSFERENCIA` e exibe snackbar de confirmação.
* **Ação de Debug — `[Simular Item em Separação]`:**
  * Acessível **apenas** quando o "Modo Debug NFC" está habilitado nas Configurações (Seção 3.6).
  * Exibida como botão secundário (ícone de engrenagem pequeno) no card de cada item do catálogo.
  * Ao clicar, alterna a flag `emSeparacao` do item e exibe Snackbar: *"Item `[SKU]` marcado como 'Em Separação'. Use este estado para testar o bloqueio na tela de Separação."*
  * Objetivo: permitir que a banca demonstre ao vivo o comportamento do Cenário 5.5 (Retirada Simultânea) sem precisar de dois dispositivos ou dois operadores reais.

### 3.4. Módulo de Inserção de Itens (Recebimento Refinado)
Fluxo focado em velocidade operacional para entrada de mercadorias no estoque.

1. **Tela de Leitura NFC de Destino:**
   * A tela inicial não exibe formulário complexo; apresenta uma grande ilustração pulsante solicitando: **"Aproxime o celular da posição de destino na prateleira"**.
   * Abaixo da animação, oferece o botão secundário `[Selecionar Endereço Manualmente]` (útil para versão Web ou contingência).
2. **Resolução de Endereço:**
   * O operador encosta o celular na etiqueta NFC da posição física.
   * O app consulta `TagNFC` localmente. Se encontrar, emite feedback tátil/sonoro positivo e avança automaticamente para o formulário. (Se não encontrar, dispara o fluxo de exceção de Tag Não Cadastrada).
3. **Tela de Formulário Pós-NFC:**
   * **Cabeçalho Fixo:** Destaca o endereço validado (ex.: **`Estante A > Prateleira A2 > Posição A2-05`**) e o UID da Tag lida.
   * **Campos do Formulário:**
     * `Item`: *Dropdown* com barra de busca integrada (busca por SKU ou Nome) + botão de atalho `[+ Novo Item]` caso o item ainda não exista no catálogo.
     * `Quantidade`: Componente *Stepper* (`[-]` e `[+]`) integrado a um campo de *input* numérico direto.
     * `Observação`: Campo de texto opcional (ex.: número da Nota Fiscal, lote ou fornecedor).
   * **Regras de Validação do Formulário:**
     * O botão primário **`[Confirmar Entrada]`** só é habilitado (ou valida ao clique) se: `Posição` for válida, um `Item` estiver selecionado e `Quantidade` for $> 0$.
4. **Pós-Confirmação:**
   * O sistema incrementa `quantidade_estoque` do item, vincula o item à `posicao_id` e grava um registro em `Movimentacao` (`ENTRADA`).
   * Exibe uma *Snackbar* em destaque: **"Entrada registrada: [Item] $\times$ [Qtd] em [Endereço]"**.
   * Apresenta dois botões de decisão rápida no rodapé:
     * **`[Nova Entrada]`**: Limpa os campos do formulário mantendo ou retornando à leitura NFC para guardar o próximo lote.
     * **`[Voltar ao Dashboard]`**: Encerra o recebimento e retorna à tela inicial.

### 3.5. Módulo de Retirada (Separação com Pick-by-Light Refinado)
O núcleo da demonstração prática do TCC, integrando software e hardware de sinalização luminosa passo a passo.

1. **Tela de Seleção e Resumo do Pedido (Pré-Coleta):**
   * O operador busca e seleciona um ou mais itens para retirada (definindo as quantidades desejadas).
   * Antes de acionar o hardware, o app exibe a **Tela de Resumo do Pedido**:
     * Lista ordenada dos itens a separar, contendo: Nome/SKU, Quantidade Solicitada e Endereço Completo (`Estante > Prateleira > Posição`).
     * Botão primário em destaque na base da tela: **`[Iniciar Coleta]`**.
2. **Sequenciamento Inteligente (Um Item por Vez):**
   * Ao clicar em `[Iniciar Coleta]`, o aplicativo ativa o lock otimista (`emSeparacao = true`) nos itens da lista e inicia o **sequenciamento guiado**, acendendo os LEDs de **um item por vez** (seguindo a rota otimizada mockada por ordem de código de endereço).
   * O topo da tela exibe uma barra de progresso clara: **"Item 1 de 3"**.
3. **Disparo MQTT e Tela de Validação NFC (Poka-Yoke):**
   * O app publica o comando `ACENDER` via MQTT (QoS 1) para a posição do item atual e aguarda o `ACK` do ESP32.
   * A **Tela de Validação NFC** exibe:
     * **Endereço Esperado:** Ex.: `Estante A > Prateleira A2 > Posição A2-05`.
     * **Status do LED:** Badge visual indicando a cor acesa e o ID do hardware (ex.: 🟢 **`LED VERDE ACESO (POS_A2_05)`**).
     * **Instrução Visual:** Animação de *"Aproxime o celular da etiqueta NFC na posição iluminada"*.
     * **Ação de Contingência:** Botão secundário **`[Cancelar Coleta]`** (se acionado, envia MQTT `APAGAR`, libera o lock do item e retorna ao Dashboard).
4. **Verificação Física por NFC (Detecção de Erros):**
   * **Cenário A — Tag Incorreta (Erro de Posição):** Se o operador encostar o celular na etiqueta de outra posição, a tela pisca em **vermelho**, o celular executa 1 vibração longa ($600\text{ ms}$) + som grave e exibe modal: **"Local incorreto! Você bipou [Posição Lida], mas o item está em [Posição Esperada]. Verifique a luz verde."**
   * **Cenário B — Tag Correta (Sucesso):** Se o UID corresponder exatamente à posição iluminada, o app executa 1 vibração curta ($150\text{ ms}$), transita o fundo para **verde** e exibe: **"Local correto! Confirme a quantidade."**
5. **Confirmação de Quantidade e Baixa:**
   * Exibe o *Stepper* de quantidade pré-preenchido com a quantidade solicitada (validando que a retirada seja $> 0$ e $\le \text{saldo disponível}$).
   * O operador clica em **`[Finalizar Retirada]`**.
   * O app debita o saldo de `Item`, registra a `Movimentacao` (`SAIDA`), libera o lock (`emSeparacao = false`) e publica imediatamente o comando MQTT **`APAGAR`** para os LEDs daquela posição.
   * Se houver mais itens na fila, avança automaticamente para o **"Item 2 de 3"** (acendendo o próximo endereço).
6. **Conclusão da Coleta:**
   * Ao finalizar o último item da lista, exibe a **Tela de Sucesso**: ícone animado de conclusão + mensagem **"Coleta finalizada! 3 itens separados."** + resumo da operação e botão **`[Voltar ao Dashboard]`**.

### 3.6. Tela de Ajustes e Configurações

Acessível por ícone de engrenagem no canto superior direito do Dashboard. Agrupa configurações operacionais e de apresentação, organizadas em cartões expansíveis:

* **Conectividade MQTT:**
  * Campo de texto editável `URL do Broker` (default: lido de `DataStore`/`SharedPreferences`, com fallback `tcp://192.168.1.100:1883`).
  * Botão **`[Testar Conexão]`**: dispara `PING` via MQTT e exibe latência ($\text{ms}$) ou mensagem de erro.
  * Exibição em tempo real do `MqttConnectionState` (badge colorido).
  * Botão **`[Reconectar Agora]`**: força tentativa imediata de reconexão.
* **Acessibilidade e Feedback:**
  * Switch `Fonte Ampliada`: aplica escala tipográfica de $1.25\times$ em toda a UI.
  * Switch `Feedback Sonoro`: habilita/desabilita bipes de confirmação e erro.
  * Switch `Feedback Tátil (Vibração)`: habilita/desabilita vibrações.
  * Slider `Volume dos Bipes` ($0\text{–}100\%$).
* **Dados e Demonstração (Mock):**
  * Botão **`[Resetar Dados de Demonstração]`**: apaga o banco Room local e recarrega o *seed* inicial (útil antes de apresentações).
  * Botão **`[Exportar Snapshot JSON]`**: gera arquivo JSON com todo o estado atual (endereços, itens, movimentações) e compartilha via `Intent.ACTION_SEND`.
  * Toggle `Modo Debug NFC`: exibe botão flutuante `[Simular Leitura NFC]` em todas as telas que aceitam leitura.
* **Sobre:**
  * Versão do app, versão do Compose Multiplatform, build hash.
  * Link para log de conexão MQTT (últimas 50 mensagens enviadas/recebidas, em formato cronológico).

### 3.7. Comportamento do Botão Voltar (Back) em Fluxos Críticos

Para evitar perda de dados ou estados inconsistentes, o botão físico "Voltar" (Android) e o gesto de swipe (iOS/Web) devem ser interceptados (`BackHandler` no Compose) nos seguintes contextos:

| Contexto | Comportamento ao Pressionar Voltar |
| :--- | :--- |
| **Recebimento — formulário preenchido (não vazio)** | Exibir dialog de confirmação: *"Descartar entrada em andamento?"* com opções `[Descartar]` e `[Continuar Preenchendo]`. Se descartar, limpa campos e retorna à leitura NFC. |
| **Recebimento — formulário vazio** | Retorna diretamente à tela de leitura NFC (sem dialog). |
| **Separação — coleta ativa (LED aceso)** | Exibir dialog: *"Cancelar coleta? O LED será apagado e o item será liberado."* com opções `[Cancelar Coleta]` (envia MQTT `APAGAR`, libera lock `emSeparacao = false`, retorna ao Dashboard) e `[Continuar Coleta]`. |
| **Separação — resumo do pedido (antes do `[Iniciar Coleta]`)** | Retorna à tela de busca sem confirmação (nada foi acionado). |
| **Cadastro de Posição — modal NFC aberto** | Fecha o modal e retorna ao formulário com campos preservados. |
| **Dashboard** | Comportamento padrão do sistema (fecha o app). |

Nenhum fluxo crítico deve permitir saída silenciosa do estado atual sem confirmação explícita do operador.

### 3.8. Tela de Histórico de Movimentações (Auditoria)

Acessível pelo Dashboard via novo botão **`[Histórico]`** (ícone de lista no canto superior, ao lado da engrenagem de Ajustes). Permite rastrear todas as operações realizadas, atendendo ao requisito de rastreabilidade do TCC (Seção 2.2 do referencial teórico).

* **Filtros no topo da tela:**
  * Chips: `[Todas]`, `[Entradas]`, `[Saídas]`, `[Transferências]`, `[Modo Manual]`.
  * Seletor de data (últimos 7 dias / 30 dias / customizado).
  * Busca por `SKU` ou `código de endereço`.
* **Lista em formato cronológico reverso (mais recente primeiro):**
  * Cada item exibe: ícone do tipo (verde/vermelho/azul), `Item [SKU]`, `Quantidade × [N]`, `Endereço completo`, `Timestamp formatado (dd/MM/yyyy HH:mm:ss)`, `Usuário`.
  * Se `modoManual == true`, exibir badge laranja *"⚠️ Modo Manual"* ao lado do endereço.
* **Ações por item:**
  * Clique abre um *BottomSheet* com todos os detalhes (incluindo `id` UUID, `observacao`, `modoManual`).
* **Estado vazio (`EmptyState`):** Ilustração + texto *"Nenhuma movimentação registrada ainda. Inicie uma entrada ou separação."* + botão `[Ir para Recebimento]`.
* **Exportação:** Botão `[Exportar CSV]` no topo gera arquivo das movimentações filtradas e compartilha via `Intent`.

> *Nota: Esta tela atende ao critério de aceite de rastreabilidade do TCC (Seção 2.2 do documento de referência) e será útil tanto para o App quanto para a banca examinadora validar a corretude dos registros.*

---

## 4. Arquitetura e Especificação MQTT Refinada

### 4.1. `MqttService` Singleton e Gerenciamento de Estado
A comunicação MQTT deve ser isolada em uma classe singleton `MqttService` injetada nos ViewModels, expondo reativamente o estado da conexão via Kotlin Coroutines `StateFlow`:

```kotlin
package com.uniso.wms.pickbylight.data.mqtt

import kotlinx.coroutines.flow.StateFlow

enum class MqttConnectionState {
    CONNECTED,
    DISCONNECTED,
    CONNECTING,
    ERROR
}

interface MqttService {
    val connectionState: StateFlow<MqttConnectionState>
    val pendingCommandsCount: StateFlow<Int>

    /**
     * Conecta ao broker MQTT.
     * @param brokerUrl URL do broker. Se null, usa o valor persistido em DataStore/SharedPreferences.
     *                  Se nenhum valor estiver persistido, usa fallback do BuildConfig (não hardcoded na interface).
     */
    suspend fun connect(brokerUrl: String? = null)
    suspend fun acenderLed(estanteLedId: String, prateleiraLedId: String, posicaoLedId: String, cor: String = "VERDE"): Result<Unit>
    suspend fun apagarLed(estanteLedId: String, prateleiraLedId: String, posicaoLedId: String): Result<Unit>
    suspend fun testarLed(estanteLedId: String, prateleiraLedId: String?, posicaoLedId: String?, cor: String = "AZUL", duracaoMs: Int = 3000): Result<Unit>
    suspend fun enviarPing(): Result<Long> // Retorna latência em ms
    fun disconnect()
}
```

> *"A URL do broker **nunca** deve estar hardcoded no código-fonte da interface. A resolução em ordem de prioridade é: (1) parâmetro explícito, (2) valor persistido em `DataStore` via Tela de Ajustes (Seção 3.6), (3) fallback em `BuildConfig.MQTT_BROKER_URL` configurado por variável de ambiente no `local.properties`/`gradle.properties`. O fallback padrão `tcp://192.168.1.100:1883` é apenas exemplo de desenvolvimento."*

### 4.2. Parâmetros de Rede, QoS, Reconexão e LWT
* **Broker Padrão:** Configurável na **Tela de Ajustes (Seção 3.6)** para facilitar a conexão na rede Wi-Fi da banca/laboratório (fallback de desenvolvimento: `tcp://192.168.1.100:1883`).
* **Qualidade de Serviço (QoS):** Todos os comandos de acionamento (`ACENDER`, `APAGAR`, `TESTE_LED`) e confirmações (`ACK`) devem utilizar **QoS 1 (*At least once*)**, garantindo a entrega da mensagem na camada física.
* **Reconexão Automática com Backoff Exponencial:** Em caso de perda de conexão com o Broker, o `MqttService` deve tentar reconectar automaticamente aplicando *exponential backoff* (`1s`, `2s`, `4s`, `8s`, limitado ao teto de `16s`).
* **Last Will and Testament (LWT):** Na conexão inicial, o app registra uma mensagem LWT no tópico `wms/pickbylight/status/app` com payload `{"client": "wms_mobile", "status": "OFFLINE"}` (Retained = true, QoS 1), permitindo que o Broker e o ESP32 apaguem luzes órfãs caso o app feche abruptamente.

| Tópico MQTT | Direção | QoS | Finalidade | Usado por |
| :--- | :--- | :---: | :--- | :--- |
| `wms/pickbylight/comando` | App -> ESP32 | **1** | Envio de comandos `ACENDER`, `APAGAR`, `PING` e `TESTE_LED`. | Separação (`ACENDER`/`APAGAR`), Dashboard/Ajustes (`PING`) e Cadastro de Prateleira/Posição (`TESTE_LED`). |
| `wms/pickbylight/ack` | ESP32 -> App | **1** | Recebimento de confirmação de execução física (`ACK` / `PONG`) vindo do ESP32. | Confirmação de `ACENDER`, `APAGAR`, `TESTE_LED` e `PING`. |
| `wms/pickbylight/status/app` | App -> Broker | **1** | Tópico de *Last Will and Testament* (LWT) e estado online do app. | Gerenciamento de sessão do `MqttService`. |

### 4.3. Contratos de Payload JSON

**1. Comando Iniciar Coleta (`ACENDER`) — Tópico: `wms/pickbylight/comando`**
```json
{
  "acao": "ACENDER",
  "estante_led_id": "EST_A_LED1",
  "prateleira_led_id": "PRAT_A2_LED1",
  "posicao_led_id": "POS_A2_05",
  "cor": "VERDE"
}
```

**2. Comando Finalizar Retirada (`APAGAR`) — Tópico: `wms/pickbylight/comando`**
```json
{
  "acao": "APAGAR",
  "estante_led_id": "EST_A_LED1",
  "prateleira_led_id": "PRAT_A2_LED1",
  "posicao_led_id": "POS_A2_05"
}
```

**3. Resposta de Confirmação do Hardware (`ACK`) — Tópico: `wms/pickbylight/ack`**
```json
{
  "acao": "ACK",
  "posicao_led_id": "POS_A2_05",
  "status": "OK"
}
```
*(Valores aceitos para `status`: `"OK"` ou `"ERRO"`).*

**4. Comando de Teste de Conectividade (`PING`) — Tópico: `wms/pickbylight/comando`**
```json
{
  "acao": "PING"
}
```

**5. Comando de Teste Individual de LED (`TESTE_LED`) — Tópico: `wms/pickbylight/comando`**
```json
{
  "acao": "TESTE_LED",
  "estante_led_id": "EST_A_LED1",
  "prateleira_led_id": "PRAT_A2_LED1",
  "posicao_led_id": "POS_A2_05",
  "cor": "AZUL",
  "duracao_ms": 3000
}
```
*Observações:*
- *Disparado pelos botões `[Testar LED]` das telas de cadastro de Prateleira e Posição.*
- *Os campos devem ser preenchidos com o máximo de granularidade disponível no contexto (ex.: se testar apenas a Prateleira, enviar `posicao_led_id: null`).*
- *Cor padrão de teste: `AZUL` (distinta da cor de operação `VERDE`).*
- *O ESP32 deve apagar o LED automaticamente após `duracao_ms` (default $3\text{ s}$), sem necessidade de comando `APAGAR` adicional.*
- *Resposta esperada no tópico `ack`: `{"acao":"ACK","posicao_led_id":"...","status":"OK"}`.*

---

## 5. Tratamento de Falhas e Timeouts (Casos de Exceção)

Para atender aos requisitos de confiabilidade e detecção/correção de erros na camada física, o aplicativo deve implementar rigorosamente os 5 tratamentos abaixo:

1. **Broker MQTT Indisponível:**
   * **Comportamento:** Quando `connectionState` estiver em `DISCONNECTED` ou `ERROR`, exibir um **banner superior de aviso persistente** em todas as telas: *"Broker MQTT desconectado. Tentando reconectar..."*.
   * **Fila e Retry:** Comandos disparados durante a queda são inseridos em uma **fila local de comandos pendentes** e reenviados com *retry exponencial* (máximo de **3 tentativas**). Se falhar após 3 tentativas, orientar o operador a seguir pela indicação textual de endereço na tela.
2. **ESP32 Sem Resposta (Timeout de ACK em $5\text{ s}$):**
   * **Comportamento:** Ao publicar um comando `ACENDER`, o aplicativo inicia um temporizador de **$5\text{ segundos}$** aguardando a mensagem correspondente no tópico `wms/pickbylight/ack`.
   * **Ação de Falha:** Caso o `ACK` não seja recebido em $5\text{ s}$ (ou retorne `"status": "ERRO"`), exibir um Modal de Alerta: **"Hardware não respondeu. Verifique a conexão Wi-Fi e a alimentação dos LEDs."** contendo:
     * Botão primário **`[Tentar Novamente]`**: refaz o comando `ACENDER` e reinicia o timer de $5\text{ s}$.
     * Botão secundário **`[Continuar em Modo Manual]`**: ativa um modo degradado de operação com o seguinte comportamento:
       * **Exibe um banner vermelho fixo no topo:** *"MODO MANUAL — LED indisponível. Validação por endereço textual."*
       * **Preserva a validação NFC (Poka-Yoke)**: o operador **ainda deve** bipar a tag NFC correta da posição esperada (o app continua bloqueando endereços errados).
       * **Adiciona um alerta textual na Tela de Validação NFC** com o endereço esperado em destaque para guiar o operador visualmente (já que não há LED físico).
       * **Registra o flag `modo_manual = true`** no registro de `Movimentacao` gerado ao finalizar aquela retirada, para fins de auditoria/relatório.
       * **Libera o modo** automaticamente ao retornar ao Dashboard ou ao receber ACK de um novo comando MQTT bem-sucedido.
3. **Timeout de Confirmação Local ($60\text{ s}$ sem Bipagem NFC):**
   * **Comportamento:** Após o LED acender na maquete durante a Separação, inicia-se uma contagem regressiva visível de **$60\text{ segundos}$** para que o operador realize a bipagem NFC na posição.
   * **Ação de Expiração:** Se o tempo esgotar sem leitura NFC válida, o app envia automaticamente o comando MQTT **`APAGAR`** para proteger o hardware, libera o lock do item e retorna à tela de busca exibindo a mensagem: **"Tempo esgotado. Operação cancelada."**
4. **Tag NFC Não Cadastrada:**
   * **Comportamento:** Se o operador encostar o celular em uma etiqueta NFC cujo UID não exista na tabela `TagNFC`:
   * **Ação de Falha:** Acionar **vibração longa ($600\text{ ms}$) + tela vermelha** e abrir um Modal de Exceção informando o UID lido e a mensagem *"Tag NFC não cadastrada no sistema!"*, oferecendo o botão de ação rápida **`[Cadastrar Agora]`** (que redireciona imediatamente para a tela de Gestão de Endereços já levando o UID preenchido em memória).
5. **Retirada Simultânea do Mesmo Item (Concorrência / Lock Otimista):**
   * **Comportamento:** Antes de iniciar a separação de um item, o app verifica a flag em memória/banco local `item.emSeparacao`.
   * **Ação de Bloqueio:** Se `emSeparacao == true` (simulando outro terminal operando sobre o mesmo SKU ou saldo reservado), bloquear o início da coleta e exibir um alerta: **"Item em separação por outro operador"**. (O bloqueio pode ser demonstrado ao vivo para a banca ativando o botão `[Simular Item em Separação]` no Catálogo de Itens — Seção 3.3).

---

## 6. Plano de Testes do App (Mock) e Critérios de Aceite

### 6.1. Cenários de Teste Homologados
Lista de verificação para validação funcional no Antigravity e nos testes integrados com a maquete física:

- [ ] **Cenário 1 (Inserção com tag válida):** Aproximar o celular de uma tag NFC cadastrada (`POS_A2_05`) -> O app vibra curto e abre automaticamente o formulário de Recebimento preenchido com o endereço `Estante A > Prat A2 > Pos A2-05`.
- [ ] **Cenário 2 (Inserção com tag inválida):** Aproximar uma tag NFC virgem/desconhecida -> O app vibra longo, exibe tela vermelha e abre modal de erro com o botão `[Cadastrar Agora]`.
- [ ] **Cenário 3 (Retirada com tag correta):** Iniciar separação -> LED físico acende (`ACK` recebido em $< 5\text{ s}$) -> Bipar a tag NFC correspondente à posição iluminada -> Tela fica verde e avança para o *stepper* de confirmação de quantidade.
- [ ] **Cenário 4 (Retirada com tag errada - Poka-Yoke):** Com a coleta ativa para `POS_A2_05`, bipar a tag da posição `POS_A1_01` -> Tela pisca em vermelho, celular vibra longo e exibe modal *"Local incorreto! Verifique a luz."* bloqueando a baixa.
- [ ] **Cenário 5 (Broker MQTT offline):** Desligar o Broker na rede -> App exibe banner persistente de desconexão, enfileira o comando de acionamento e executa até 3 tentativas de retry com backoff exponencial.
- [ ] **Cenário 6 (Timeout de operação de $60\text{ s}$):** Iniciar coleta (LED acende) e aguardar $60\text{ s}$ sem aproximar o celular da tag -> O app dispara comando MQTT `APAGAR` automaticamente e retorna à tela de busca com o aviso *"Tempo esgotado. Operação cancelada."*
- [ ] **Cenário 7 (Responsividade e Rotação):** Rotacionar o smartphone (Portrait <-> Landscape) e redimensionar a janela em tela `expanded` (Tablet/Web) -> O estado do formulário/coleta é preservado sem perda de dados e a navegação alterna entre *Bottom Bar*, *Navigation Rail* e *Side Drawer*.
- [ ] **Cenário 8 (Modo Manual):** Simular falha de ACK (desligar ESP32) $\rightarrow$ no modal de timeout, clicar em `[Continuar em Modo Manual]` $\rightarrow$ confirmar banner vermelho fixo, validação NFC ainda ativa e flag `modo_manual = true` no registro de `Movimentacao` ao finalizar.
- [ ] **Cenário 9 (Back em fluxo crítico):** Iniciar separação com LED aceso $\rightarrow$ pressionar Back físico $\rightarrow$ confirmar dialog "Cancelar coleta?" $\rightarrow$ escolher `[Continuar Coleta]` $\rightarrow$ verificar que LED permanece aceso e estado preservado.
- [ ] **Cenário 10 (Histórico):** Realizar 1 entrada + 1 saída $\rightarrow$ abrir tela de Histórico $\rightarrow$ verificar 2 registros com timestamps, endereços e badges corretos $\rightarrow$ testar exportação CSV.

### 6.2. Critérios de Aceite (Qualidade e Desempenho)
- [ ] **$100\%$ de Confiabilidade na Validação NFC:** Nenhuma operação de baixa ou entrada pode ser registrada em endereço divergente da tag física lida (zero falsos positivos).
- [ ] **Baixa Latência Operacional ($< 2\text{ s}$):** O tempo decorrido entre a aproximação física da etiqueta NFC (ou clique em `[Iniciar Coleta]`) e o feedback visual/tátil na tela + acionamento do LED via MQTT deve ser estritamente inferior a $2\text{ segundos}$ em rede local.
- [ ] **Zero Crashes em Cenários de Exceção:** Quedas de Wi-Fi, Broker indisponível, ESP32 desligado ou leituras repetidas de tags NFC não devem causar fechamento inesperado (*Force Close*) da aplicação em nenhuma hipótese.

---

## 7. Próximos Passos — Roadmap de Evolução (Fase 2)

A arquitetura modular construída na Fase 1 prepara o terreno para as seguintes expansões na Fase 2 do projeto:

1. **API REST e Banco de Dados Relacional Real:** Substituir a camada de repositório mock/local por chamadas HTTP via **Retrofit / Ktor Client**, consumindo o backend desenvolvido em **Java com Spring Boot** e persistindo dados em **PostgreSQL**.
2. **Autenticação e Controle de Acesso (RBAC):** Implementar login com **JWT (JSON Web Token)** para identificar individualmente cada operador nas auditorias de `Movimentacao` e restringir edições de catálogo/endereços a perfis de Supervisor.
3. **Versão Web Completa (Kotlin/Wasm):** Ativar o *target* Web do Compose Multiplatform para que o gerenciamento em massa de endereços, catálogos e expedição seja operado em computadores de mesa.
4. **Integração com Leitor RFID Físico (RC522 via ESP32):** Habilitar o módulo leitor **RFID-RC522** conectado diretamente ao ESP32 na maquete como alternativa (*fallback* ou operação conjunta via tópico MQTT `wms/pickbylight/rfid/leitura`) ao leitor NFC do smartphone.
5. **Dashboard Analítico e KPIs Logísticos:** Adicionar módulo gerencial com gráficos de desempenho: tempo médio de separação (*picking time*), taxa de erros evitados pelo *Poka-Yoke*, curva ABC de itens mais movimentados e ocupação por estante.
6. **Modo Offline-First com Sincronização Automática:** Manter o **Room Database** como fonte primária de verdade (*Single Source of Truth*) no dispositivo móvel e utilizar `WorkManager` para sincronizar entradas e baixas em *background* (incluindo a sincronização do campo `modoManual` das movimentações para relatórios consolidados de confiabilidade) assim que a conectividade com a API Spring Boot for restabelecida.
7. **Internacionalização e Múltiplos Idiomas (i18n):** Externalizar todas as strings da interface para suportar alternância dinâmica entre Português (`pt-BR`), Inglês (`en-US`) e Espanhol (`es-ES`).
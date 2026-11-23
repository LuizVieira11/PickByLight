# 📦 WMS Pick-by-Light — Sistema de Gerenciamento de Armazém com IoT (MQTT) e NFC

Sistema **WMS (Warehouse Management System)** multiplataforma desenvolvido em **Kotlin Multiplatform (KMP)** e **Compose Multiplatform**, integrando dispositivos móveis **Android** (com leitura física NFC e cliente nativo MQTT Eclipse Paho) e interface **Web/Wasm** ao protótipo físico de estantes iluminadas controladas por **ESP32 + LEDs discretos monocromáticos, um por canal GPIO**.

---

## 🏗️ Arquitetura do Projeto

O código-fonte segue os princípios de **Clean Architecture + MVVM + UDF (Unidirectional Data Flow)** sob o pacote base `com.uniso.wms.pickbylight`:

```
PickByLight/
├── app/
│   ├── shared/                 # Módulo KMP compartilhado (commonMain, androidMain, wasmJsMain, commonTest)
│   │   └── src/
│   │       ├── commonMain/     # Domínio, Repositórios, ViewModels UDF, Telas Compose M3 e Tema Industrial
│   │       ├── androidMain/    # Implementações nativas Android (Paho MQTT QoS 1, DataStore, NFC ReaderMode, Vibração/Bipes)
│   │       ├── wasmJsMain/     # Implementações Web/Wasm
│   │       └── commonTest/     # Suíte completa de testes unitários e cenários de homologação
│   ├── androidApp/             # Entry-point do aplicativo Android (MainActivity, AndroidManifest, BuildConfig)
│   └── webApp/                 # Entry-point da aplicação Web (Kotlin/Wasm)
├── core/                       # Utilitários multiplataforma compartilhados
├── esp32/                      # Firmware ESP32 v1, secrets.example.h e roteiro de bancada
├── tools/                      # Simuladores auxiliares (ex.: esp_sim.py)
└── especificacao/
    ├── ESPECIFICACAO.md        # Especificação Técnica Vinculante (Contratos Legados e Fluxos)
    └── MQTT5_E_ESP32.md        # Especificação do Gerenciamento de ESPs, MQTT 5/3.1.1 e Firmware
```

---

## ✨ Funcionalidades Implementadas

1. **Dashboard Operacional (Seção 3.1):**
   - Indicador reativo de conexão MQTT (`Conectado`, `Reconectando...`, `Offline`, `Erro MQTT`) exibindo a URL persistida no `DataStore`.
   - Cards executivos em tempo real (`Total Itens`, `Movimentos Entradas/Saídas`, `Posições NFC Mapeadas`, `Estoque Baixo`).
   - Diagnóstico rápido de hardware (`PING` com medição de latência em ms e `TESTE_LED`).
2. **Gestão de Endereços em Cascata e Vinculação NFC (Seção 3.2):**
   - Árvore expansível em 3 níveis (`Estante` -> `Prateleira` -> `Posição`) com filtros (`Todas`, `Com NFC`, `Sem NFC ⚠️`).
   - Validação de limites físicos L×A×P (`^\d{1,4}x\d{1,4}x\d{1,4}(cm|mm)$`).
   - Leitura NFC *read-only* (`NfcAdapter.enableReaderMode`) gravando exclusivamente na entidade `TagNFC` com validação hexadecimal de 7 bytes, debounce de 2s e verificação de unicidade.
3. **Catálogo de Itens e Estoque por Posição (Seção 3.3):**
   - Separação arquitetural entre catálogo mestre (`Item`) e saldos físicos (`EstoquePosicao`), permitindo múltiplos endereços por SKU.
   - Transferência interna (`[Mover Item]`) com suporte a seleção manual ou leitura NFC e registro em `Movimentacao(TRANSFERENCIA)`.
   - Simulação de concorrência (`[Simular Item em Separação]`) quando o *Modo Debug NFC* está ativo.
4. **Recebimento de Mercadorias — Modos Único e Múltiplo Guiado (Seção 3.4):**
   - **Modo Único:** Leitura NFC da posição de destino → Formulário com busca de item, cadastro rápido (`[+ Novo Item]`) e *Stepper* numérico → Atualização de `EstoquePosicao` e gravação de `Movimentacao(ENTRADA)`.
   - **Modo Múltiplo (Guiado):** Montagem prévia do lote de recebimento e execução guiada passo a passo (`Passo X de Y`), bipando a etiqueta NFC de guarda de cada item.
5. **Separação de Pedidos Pick-by-Light (Seção 3.5 e Seção 5):**
   - **Algoritmo Best Fit + Fracionamento + Wave Picking:** Prioriza esvaziar o menor saldo suficiente (*Best Fit*), divide automaticamente entre múltiplas posições quando necessário (*Split*) e consolida paradas na mesma prateleira (*Wave Picking*).
   - **Execução Sequencial:** Acende um LED verde por vez (`ACENDER` com QoS 1), aguardando confirmação `ACK` do ESP32 em até **5 segundos** e contagem regressiva de **60 segundos** para bipagem NFC.
   - **Poka-Yoke NFC e Picking Flexível:** Valida a etiqueta física da posição iluminada e aceita dinamicamente outra posição caso ela possua o item com saldo suficiente, bloqueando com alerta vermelho e vibração longa (`600ms`) posições sem saldo ou tags desconhecidas.
   - **Modo Manual Degradado:** Em caso de falha do hardware IoT, guia o operador por endereço textual preservando o Poka-Yoke NFC e registrando `modoManual = true` na `Movimentacao(SAIDA)`.
6. **Ajustes, Persistência DataStore e Histórico com Exportação CSV (Seções 3.6 e 3.8):**
   - Persistência da URL do Broker MQTT via `DataStore<Preferences>` com reconexão imediata.
   - Histórico auditável com filtros por tipo (`Todas`, `Entradas`, `Saídas`, `Transferências`, `Modo Manual`), período, busca por SKU/endereço, modal de detalhes (UUID) e exportação CSV.

---

## 📡 Protocolo e Tópicos MQTT (QoS 1)

| Tópico MQTT | Direção | QoS | Descrição |
| :--- | :---: | :---: | :--- |
| `wms/pickbylight/comando` | App → ESP32 | **1** | Comandos JSON: `ACENDER`, `APAGAR`, `PING` e `TESTE_LED`. |
| `wms/pickbylight/ack` | ESP32 → App | **1** | Confirmação de execução física (`{"acao":"ACK","posicao_led_id":"...","status":"OK"}`). |
| `wms/pickbylight/status/app` | App → Broker | **1** | *Last Will and Testament* (LWT `OFFLINE` / sessão `ONLINE` com `retained = true`). |

### Configuração Inicial do Broker MQTT
1. **Via `local.properties` (Fallback de compilação):**
   Adicione no arquivo `local.properties` na raiz do projeto:
   ```properties
   MQTT_BROKER_URL=tcp://192.168.1.100:1883
   ```
2. **Em Tempo de Execução (Prioridade Máxima):**
   Acesse **Dashboard → ⚙️ Ajustes**, digite a nova URL do Broker (ex.: `tcp://192.168.15.8:1883`) e clique em **`[Salvar e Reconectar Agora]`**. O valor é persistido localmente no `DataStore`.

---

## 🚀 Como Compilar, Executar e Testar (Windows)

### 1. Executar os Testes Unitários e de Homologação (Android Host)
```bat
gradlew.bat :app:shared:testAndroidHostTest --rerun-tasks --console=plain
```

### 2. Compilar o módulo Web/Wasm
```bat
gradlew.bat :app:shared:compileKotlinWasmJs --console=plain
```

### 3. Gerar o APK de Debug para Android
```bat
gradlew.bat :app:androidApp:assembleDebug --console=plain
```
O APK será gerado em `app/androidApp/build/outputs/apk/debug/androidApp-debug.apk`.

### 4. Executar a Versão Web (Kotlin/Wasm)
```bat
gradlew.bat :app:webApp:wasmJsBrowserDevelopmentRun
```

---

## ✅ Auditoria dos 10 Cenários de Homologação (Seção 6.1)

Todos os 10 cenários da Seção 6.1 possuem cobertura automatizada em [`HomologacaoCenariosTest.kt`](./app/shared/src/commonTest/kotlin/com/uniso/wms/pickbylight/HomologacaoCenariosTest.kt):

- [x] **Cenário 1 (Inserção com tag válida):** Leitura da tag de `POS_A2_05` (`04:A1:2B:3C:4D:5E:04`) resolve `Estante A > PRAT-A2 > POS-05` e avança ao formulário.
- [x] **Cenário 2 (Inserção com tag inválida):** Leitura de UID desconhecido aciona tela vermelha, vibração longa e modal com atalho `[Cadastrar Agora]`.
- [x] **Cenário 3 (Retirada com tag correta):** Início da separação publica `ACENDER`, recebe `ACK` (< 5s) e, ao bipar a tag correta, transita para confirmação verde.
- [x] **Cenário 4 (Retirada com tag errada - Poka-Yoke):** Bipagem de posição sem saldo do item pisca tela vermelha e bloqueia a baixa com modal `"Local incorreto!"`.
- [x] **Cenário 5 (Broker MQTT offline):** Desconexão exibe banner persistente e enfileira comandos pendentes com retry exponencial.
- [x] **Cenário 6 (Timeout de operação de 60s):** Expiração sem leitura NFC dispara `APAGAR` automaticamente, libera o lock otimista e exibe `"Tempo esgotado. Operação cancelada."`.
- [x] **Cenário 7 (Responsividade e Rotação):** Suporte adaptativo `Compact` / `Medium` / `Expanded` e preservação de estado via `ViewModel` durante rotação de tela.
- [x] **Cenário 8 (Modo Manual):** Timeout de ACK de 5s permite ativar `[Continuar em Modo Manual]` com banner vermelho fixo, Poka-Yoke NFC ativo e gravação de `modoManual = true`.
- [x] **Cenário 9 (Back em fluxo crítico):** Interceptação do botão Voltar durante coleta ativa com opções `[Continuar Coleta]` (preserva estado) e `[Cancelar Coleta]` (apaga LED e libera lock).
- [x] **Cenário 10 (Histórico e Exportação CSV):** Registro cronológico reverso de entradas, saídas e transferências com timestamps formatados (`dd/MM/yyyy HH:mm:ss`), filtros e exportação CSV.

---

## 🔌 Hardware ESP32, Broker MQTT e Simulador

Para detalhes completos do protocolo de gerenciamento v1 (`wms/pickbylight/v1/...`), consulte [`especificacao/MQTT5_E_ESP32.md`](./especificacao/MQTT5_E_ESP32.md).

### 1. Broker MQTT e Autenticação (ACL)
- O protótipo opera com **Mosquitto 2.1.2** (ex.: container Docker na rede local, porta `1883`) em modo **anônimo** (autenticação e ACL adiadas conforme Seção 16, item 15 da especificação).
- A configuração-alvo com `allow_anonymous false`, usuários por dispositivo (`wms_app` e `esp-xxxxxx`) e regras `acl_file` está documentada na Seção 12 de [`especificacao/MQTT5_E_ESP32.md`](./especificacao/MQTT5_E_ESP32.md).
- Em **Ajustes**, o app Android permite selecionar o protocolo MQTT (`Automático | 5.0 | 3.1.1`, padrão `Automático` com fallback para `3.1.1`). O firmware `esp32/esp32.ino` usa **MQTT 3.1.1** via ESP-MQTT (o core `esp32:esp32 3.3.0` pré-compilado traz `# CONFIG_MQTT_PROTOCOL_5 is not set` no `sdkconfig`).

### 2. Credenciais Locais do Firmware
Copie o template [`esp32/secrets.example.h`](./esp32/secrets.example.h) para `esp32/secrets.h` (já listado em `.gitignore`) e preencha `WIFI_SSID`, `WIFI_PASSWORD` e `MQTT_HOST`:
- Para broker anônimo, mantenha `MQTT_USER` e `MQTT_PASSWORD` como `""`.
- Se o firmware for compilado com os placeholders `<WIFI_SSID>` ou `<MQTT_HOST>`, ele registra erro no monitor serial e não tenta conectar.

### 3. Toolchain ESP32 e Gravação (`arduino-cli`)
Ambiente validado: módulo **ESP-WROOM-32** (4 MB flash, sem PSRAM), core **`esp32:esp32 3.3.0`** e biblioteca **`ArduinoJson 7.4.2`**.

```bat
"C:\Program Files\Arduino IDE\resources\app\lib\backend\resources\arduino-cli.exe" compile --fqbn esp32:esp32:esp32 esp32 --warnings all
"C:\Program Files\Arduino IDE\resources\app\lib\backend\resources\arduino-cli.exe" upload -p COM5 --fqbn esp32:esp32:esp32 esp32
```

### 4. Ligação dos LEDs e Mapa de GPIOs (3,3 V)
Ligação padrão (`active_high = true`):

```text
GPIO ──► Resistor em série ──► LED (+) ──► LED (-) ──► GND
```

- **14 GPIOs recomendados (canais `CH01`–`CH14`):** `4, 13, 16, 17, 18, 19, 21, 22, 23, 25, 26, 27, 32, 33`.
- **Cautela (fora da tabela padrão):** `2, 5, 14, 15` (`GPIO2` é reservado e não é usado como canal).
- **Proibidos:** `0, 12` (strapping críticos), `1, 3` (UART0), `6–11` (flash SPI interna) e `34, 35, 36, 39` (somente entrada).

### 5. Simulador de ESP32 sem Hardware (`tools/esp_sim.py`)
Para testar o app Android sem placa física conectada (requer `pip install --user paho-mqtt`):

```bat
python tools\esp_sim.py --host localhost --channels 14
python tools\esp_sim.py --host 192.168.1.6 --esp esp-a1b2c3 --protocol 3 --ack-all
```

Após iniciar, digite `h` + Enter para ver os comandos interativos (modo silencioso, `REVISION_MISMATCH`, perda de `config/result`, `LWT`, reboot/restore e desligamento `SHUTDOWN`).

### 6. Roteiro de Testes em Bancada
Os casos de bancada (incluindo os resultados já validados com ESP real e os pendentes) estão documentados em [`esp32/TESTES_BANCADA.md`](./esp32/TESTES_BANCADA.md).

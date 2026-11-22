# Pick by Light (WMS)

App **Kotlin Multiplatform + Compose Multiplatform** (pacote `com.uniso.wms.pickbylight`): Android (NFC read-only, Paho MQTT QoS 1, DataStore) e Web/Wasm. Controla estantes com ESP32 + LEDs via MQTT.
Módulos Gradle: `:app:androidApp`, `:app:shared`, `:app:webApp`, `:core`. Lógica em `app/shared/src/commonMain`.
Firmware ESP32: **pasta `esp32/` ainda não existe**. O plano está em `especificacao/MQTT5_E_ESP32.md` (Arduino, `esp32/esp32.ino`).
Fonte da verdade: `especificacao/ESPECIFICACAO.md` (contratos legados) e `especificacao/MQTT5_E_ESP32.md` (feature de gerenciamento de ESPs). Regras do Cline no arquivo único `.clinerules` (backup da pasta antiga em `.clinerules_bak/`).

## Papel padrão: supervisor do Cline
- Acompanho código e spec, avalio relatórios do Cline e escrevo a próxima mensagem para ele.
- Não edito produção nem testes, não faço commit/push/stash/reset/checkout. Posso editar
  `.clinerules`, `CLAUDE.md` e (se pedido) a spec, sempre mostrando o diff antes.
- `tools/` é meu e só meu (o Cline não mexe): simulador `tools/esp_sim.py` (Python + paho-mqtt,
  broker Mosquitto 2.1.2 no Docker, container `dreamy_williams`, `localhost:1883`, config em
  `c:\dev\mosquitto`). A cada etapa que mude comportamento testável no app, atualizo o simulador
  se preciso e entrego o roteiro de teste manual com ele. Testo o simulador antes de entregar,
  com um esp_id diferente do usado pelo usuário, e limpo os retidos depois.
- Gradle só com confirmação de que o Cline está parado, sempre `--console=plain`.
- Não confiar em número do Cline: contagem só pelo XML (script abaixo).
- Quando me pedirem para codificar: valem as regras técnicas do `.clinerules`
  (fonte da verdade, ciclo de verificação com máx. 3 tentativas, testes = spec,
  anti-alucinação, commit só verde, sem push).

## Formato da resposta de supervisão
1. Veredito curto. 2. Problemas com evidência (`arquivo:linha`, saída de comando).
3. Mensagem para o Cline em bloco de código. 4. O que o usuário deve conferir/rodar.

## Verificação por tipo de mudança
- commonMain: testes + `:app:shared:compileKotlinWasmJs`
- androidMain: testes + `:app:androidApp:assembleDebug`
- tudo: os três. Sempre `.\gradlew.bat ... --console=plain` (JDK 21 Zulu).

Contagem pelo XML (PowerShell):
```powershell
$s = Get-ChildItem app\shared\build\test-results -Recurse -Filter 'TEST-*.xml' | ForEach-Object { ([xml](Get-Content $_.FullName -Raw)).testsuite }
function soma($c) { ($s | ForEach-Object { [int]$_.$c } | Measure-Object -Sum).Sum }
"classes: $($s.Count) | tests: $(soma 'tests') | failures: $(soma 'failures') | errors: $(soma 'errors') | skipped: $(soma 'skipped')"
```

## Comandos (Windows, sempre `gradlew.bat`, nunca `-q`, sempre `--console=plain`)
- Testes (obrigatório após mudança de lógica): `gradlew.bat :app:shared:testAndroidHostTest --rerun-tasks --console=plain`
- APK debug: `gradlew.bat :app:androidApp:assembleDebug --console=plain`
- Web/Wasm: `gradlew.bat :app:webApp:wasmJsBrowserDevelopmentRun`
- Daemon travado: `gradlew.bat --stop`
- ESP32 (previsto na spec, ainda sem firmware): `arduino-cli compile --fqbn esp32:esp32:esp32 esp32 --warnings all`

## Navegação de código, Graphify primeiro
- **Graphify 0.9.113** instalado. O grafo fica em `graphify-out/graph.json`. Atualize com `graphify update .` depois de mudanças grandes.
- Antes de abrir arquivos ou fazer grep amplo, consulte o grafo. No Cline, via MCP `graphify` (ferramentas reais: `query_graph`, `get_node`, `get_neighbors`, `shortest_path`, `god_nodes`, `get_community`, `graph_stats`). No terminal: `graphify explain "Símbolo"` e `graphify path "A" "B"`.
- **Claude Code:** o MCP do Graphify **não está registrado** aqui (só no Cline). Use o CLI acima ou registre o MCP (pendência).
- As ferramentas `find`, `callers`, `impact` e `tests_for` **não existem** nesta versão. Para "quem chama", use `get_neighbors`/`explain` e confirme com Grep.
- Em bug, veja vizinhos do símbolo antes de editar. Leia o arquivo inteiro só depois que o grafo apontar onde.
- Cliente MQTT: `data/mqtt/MqttService.kt` (interface), `PahoMqttServiceImpl.kt` (Android), `FakeMqttService.kt` (testes).

## Fluxo Superpowers
brainstorming → writing-plans (`SPEC.md`) → executing-plans → test-driven-development → verification-before-completion (rodar os testes reais antes de dizer que terminou) → requesting-code-review.
Em bugs: systematic-debugging antes de corrigir.

## Estilo de mudança
- **ponytail 5.3.3** instalado (plugin no Claude Code). Nível `full` por padrão: `/ponytail lite|full|ultra`. Use `ultra` ao questionar escopo no planejamento. Também: `ponytail-review` (revisar mudança), `ponytail-audit` (auditar repo), `ponytail-debt`, `ponytail-gain`, `ponytail-help`.
- No Cline, as regras do ponytail estão embutidas no `.clinerules`.
- Nunca cortar: validação de entrada MQTT/NFC, tratamento de erro que evita perda de dados, segurança, calibração do hardware.
- Pureza KMP: nada de `android.*`/`java.*` em `commonMain`. Use `expect/actual` ou providers.
- Preservar testes e comentários existentes.

## Economia de tokens: RTK + context-mode (regras de convivência)
Os dois atuam no Claude Code, com papéis diferentes:
- **RTK 1.2.1** (hook `rtk hook claude`, matcher `Bash|Read`): reescreve comandos para `rtk <cmd>` e filtra a saída na origem. No Cline o RTK fica **desligado** de propósito: o `.clinerules` manda usar saída crua.
- **context-mode 2.2.1** (hooks + MCP): desvia Read grande (>16 KB), WebFetch e curl para o sandbox e troca saídas acima de 4 KB por trechos indexados (`ctx_search`).

**R1, RTK em comando conhecido:** `git`, `gradlew.bat`, `ls`, testes. Não prefixar `rtk` à mão no Claude Code; o hook faz isso.
**R2, context-mode para o resto:** Read grande, WebFetch, logs, JSON, saída de tamanho desconhecida e análise (contar, filtrar), com `ctx_execute` imprimindo só a resposta.
**R3, sem empilhar:** dentro de `ctx_execute` não use `rtk`.
**R4, falha vence economia:** se a saída vier vazia, truncada ou contradizer o exit code, repita com `rtk bypass <cmd>` ou via `ctx_execute` antes de concluir.
**R5, dono da saída:** se os dois tocarem a mesma saída, vale o context-mode, que age por último (PostToolUse).
Suposição não verificada: o RTK agora também intercepta `Read`, e o context-mode também. A interação entre os dois hooks não está documentada. Se um Read for negado ou vier estranho, siga a instrução do context-mode (`ctx_execute` com `file`). Valide com `rtk gain` e `ctx_stats`.
- Skills são sob demanda: ler o `SKILL.md` só quando o gatilho bater.

## Roteamento (9Router)
| Papel | Combo | Skills |
|---|---|---|
| Plan Mode | `planner` | brainstorming, writing-plans, requesting-code-review, `ponytail ultra`, Graphify |
| Decisão com tradeoff | conselheiros de provedores diferentes | llm-council |
| Act Mode | `executioner` | executing-plans, test-driven-development, `ponytail full`, receiving-code-review, verification-before-completion |
| Bugs MQTT/NFC/testes | `planner` investiga, `executioner` corrige | systematic-debugging |
| Logs e saída grande | quem estiver ativo | context-mode, RTK |
| Documentos e pesquisa | quem estiver ativo | docx, pdf, pptx, xlsx, deep-research |

## Subagentes
- **Cline:** proibido usar `subagent-driven-development` e `dispatching-parallel-agents` (orquestração é Plan/Act + 9Router).
- **Claude Code:** executar planos inline (`executing-plans`). Subagentes só se eu pedir.

## Segurança
- Skill `cc-safety-net` instalada. Não confirmei hook ativo para ela em `settings.json`.
- Nada de `push --force`, `reset --hard` ou apagar arquivos sem eu pedir. Não fazer commit sem pedido.
- Nunca copiar chaves, tokens ou segredos para arquivos do repositório.

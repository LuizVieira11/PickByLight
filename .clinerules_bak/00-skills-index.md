# Índice de Claude Skills Globais (Carregamento sob Demanda)

Skills globais em `C:\Users\Luiz Gustav\.claude\skills\`.
**Regra de economia de tokens:** não leia `SKILL.md` antecipadamente. Leia só o da skill cujo gatilho bateu.

## ⛔ Restrição Obrigatória (Subagentes no Cline)
- **NÃO utilize** `subagent-driven-development` nem `dispatching-parallel-agents` (mesmo que outra skill do Superpowers cite).
- **Motivo:** a orquestração é nativa do **Cline + 9Router**: **Plan Mode** = combo `planner`, **Act Mode** = combo `executioner`. Todo plano é executado direto no Act Mode.

---

## Ferramentas MCP (usar antes de ler arquivos)

**Regra "grafo primeiro":** antes de abrir arquivos ou fazer busca ampla, consulte o **Graphify** para localizar símbolos, vizinhos e caminhos. Leia o arquivo inteiro só depois que o grafo apontar onde. Em bug, veja os vizinhos do símbolo antes de editar. Se o grafo estiver defasado, rode `graphify update .`.

MCPs registrados no Cline (`C:\Users\Luiz Gustav\.cline\data\settings\cline_mcp_settings.json`):

| MCP | Uso | Combo |
| :--- | :--- | :--- |
| **`graphify`** (principal) | Grafo do código (`graphify-out/graph.json`). Ferramentas: `query_graph`, `get_node`, `get_neighbors`, `shortest_path`, `god_nodes`, `get_community`, `graph_stats`. | ambos |
| `context7` | Documentação atual de bibliotecas (Compose, Coroutines, Paho, DataStore) antes de especificar assinaturas. | `planner` |
| `memory` | Decisões e contratos de MQTT/payload entre sessões. | ambos |
| `sequential-thinking` | Raciocínio em etapas em problemas difíceis. | `planner` |
| `github` | Operações no GitHub (token já configurado; não copie o valor). | ambos |

O context-mode **não** está no Cline (só no Claude Code).

## Terminal: RTK (só por instrução no Cline)
- **R1:** prefixe comandos externos de saída longa com `rtk` (ex.: `rtk git status`, `rtk gradlew.bat ...`). O RTK 1.2.1 está instalado.
- **R4:** se o resultado vier vazio, truncado ou contradizer o exit code, repita sem filtro (`rtk bypass <cmd>` ou o comando puro).
- Nunca use `-q` no Gradle.

---

## Estilo de mudança (ponytail)

Menor mudança que resolve. Nunca cortar validação de entrada MQTT/NFC, tratamento de erro que evita perda de dados, segurança nem calibração do hardware. No Cline, o ponytail só vale após copiar `C:\Users\Luiz Gustav\.claude\plugins\cache\ponytail\ponytail\5.3.3\.clinerules\ponytail.md` para `.clinerules/` (pendente).

| Skill | Quando usar | Combo | Caminho (`SKILL.md`) |
| :--- | :--- | :--- | :--- |
| **`ponytail`** | Qualquer tarefa de código. `full` (padrão) no Act Mode; `ultra` no Plan Mode para questionar o escopo; `lite` para mudanças triviais. | `executioner` (full), `planner` (ultra) | `C:\Users\Luiz Gustav\.claude\plugins\cache\ponytail\ponytail\5.3.3\skills\ponytail\SKILL.md` |
| **`ponytail-review`** | Revisar uma mudança: lógica, segurança, testes e linhas desnecessárias. | `planner` | `C:\Users\Luiz Gustav\.claude\plugins\cache\ponytail\ponytail\5.3.3\skills\ponytail-review\SKILL.md` |
| **`ponytail-audit`** | Auditar o repositório inteiro (bugs, segurança, código sem teste). | `planner` | `C:\Users\Luiz Gustav\.claude\plugins\cache\ponytail\ponytail\5.3.3\skills\ponytail-audit\SKILL.md` |

---

## Decisão e Segurança

| Skill | Quando usar | Combo | Caminho (`SKILL.md`) |
| :--- | :--- | :--- | :--- |
| **`llm-council`** | Decisões com tradeoffs reais (*council this*, *pressure-test this*, *which option*). Use conselheiros de provedores diferentes. Não use em sim/não simples. | `planner` | `C:\Users\Luiz Gustav\.claude\skills\llm-council\SKILL.md` |
| **`cc-safety-net`** | Diagnosticar comandos bloqueados e proteção contra comandos destrutivos. | ambos | `C:\Users\Luiz Gustav\.claude\skills\cc-safety-net\SKILL.md` |

---

## Superpowers (Fluxo de Engenharia, Plan & Act)

| Skill | Quando usar | Combo | Caminho (`SKILL.md`) |
| :--- | :--- | :--- | :--- |
| **`using-superpowers`** | Referência inicial de uso do Superpowers (sem subagentes). | ambos | `C:\Users\Luiz Gustav\.claude\skills\using-superpowers\SKILL.md` |
| **`brainstorming`** | **Plan Mode:** refinar requisitos e validar design antes de especificar. | `planner` | `C:\Users\Luiz Gustav\.claude\skills\brainstorming\SKILL.md` |
| **`writing-plans`** | **Plan Mode:** plano passo a passo (`SPEC.md`) para a execução. | `planner` | `C:\Users\Luiz Gustav\.claude\skills\writing-plans\SKILL.md` |
| **`executing-plans`** | **Act Mode:** executar plano aprovado em lotes verificáveis, seguindo o `SPEC.md`. | `executioner` | `C:\Users\Luiz Gustav\.claude\skills\executing-plans\SKILL.md` |
| **`test-driven-development`** | **Act Mode:** ciclo TDD (teste falhando, implementação, verde). | `executioner` | `C:\Users\Luiz Gustav\.claude\skills\test-driven-development\SKILL.md` |
| **`systematic-debugging`** | **Plan / Act:** bugs de MQTT/NFC ou testes quebrados: causa raiz antes de corrigir. | ambos | `C:\Users\Luiz Gustav\.claude\skills\systematic-debugging\SKILL.md` |
| **`verification-before-completion`** | **Act Mode:** antes de declarar concluído, rodar `gradlew.bat :app:shared:testAndroidHostTest --rerun-tasks`. | `executioner` | `C:\Users\Luiz Gustav\.claude\skills\verification-before-completion\SKILL.md` |
| **`requesting-code-review`** | **Plan Mode:** revisar alterações contra a especificação e contratos. | `planner` | `C:\Users\Luiz Gustav\.claude\skills\requesting-code-review\SKILL.md` |
| **`receiving-code-review`** | **Act Mode:** aplicar feedback de revisão com rigor técnico. | `executioner` | `C:\Users\Luiz Gustav\.claude\skills\receiving-code-review\SKILL.md` |
| **`using-git-worktrees`** | Isolar mudanças arriscadas com `git worktree` (quando solicitado). | `executioner` | `C:\Users\Luiz Gustav\.claude\skills\using-git-worktrees\SKILL.md` |
| **`finishing-a-development-branch`** | Encerrar branch (testes finais, limpeza, merge/PR). | `executioner` | `C:\Users\Luiz Gustav\.claude\skills\finishing-a-development-branch\SKILL.md` |
| **`writing-skills`** | Criar ou refatorar `SKILL.md`. | `planner` | `C:\Users\Luiz Gustav\.claude\skills\writing-skills\SKILL.md` |

---

## Documentos e Pesquisa (sob demanda, qualquer combo)

- **`docx`**: `C:\Users\Luiz Gustav\.claude\skills\synced\c1498f0a-14cb-4bd7-a040-fb5041d47f9c_2a28a080-12d6-452a-bc79-9d1e9ef83b3b\docx\SKILL.md`
- **`pdf`**: `C:\Users\Luiz Gustav\.claude\skills\synced\c1498f0a-14cb-4bd7-a040-fb5041d47f9c_2a28a080-12d6-452a-bc79-9d1e9ef83b3b\pdf\SKILL.md`
- **`pptx`**: `C:\Users\Luiz Gustav\.claude\skills\synced\c1498f0a-14cb-4bd7-a040-fb5041d47f9c_2a28a080-12d6-452a-bc79-9d1e9ef83b3b\pptx\SKILL.md`
- **`xlsx`**: `C:\Users\Luiz Gustav\.claude\skills\synced\c1498f0a-14cb-4bd7-a040-fb5041d47f9c_2a28a080-12d6-452a-bc79-9d1e9ef83b3b\xlsx\SKILL.md`
- **`deep-research`**: `C:\Users\Luiz Gustav\.claude\skills\synced\c1498f0a-14cb-4bd7-a040-fb5041d47f9c_2a28a080-12d6-452a-bc79-9d1e9ef83b3b\deep-research\SKILL.md`

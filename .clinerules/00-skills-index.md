# Índice de Claude Skills Globais (Carregamento sob Demanda)

As skills abaixo estão instaladas globalmente em `C:\Users\Luiz Gustav\.claude\skills\`.
**Regra de economia de tokens:** Não leia os arquivos `SKILL.md` antecipadamente. Leia apenas o `SKILL.md` da skill correspondente quando a tarefa do usuário exigir ou acionar um dos gatilhos abaixo.

## ⛔ Restrição Obrigatória (Subagentes no Cline)
- **NÃO utilize** as skills `subagent-driven-development` nem `dispatching-parallel-agents` (mesmo que alguma outra skill do Superpowers faça referência a elas).
- **Motivo:** A orquestração de planejamento e execução deste projeto já é feita nativamente pelo **Cline + 9Router** (**Plan Mode** = combo `planner` e **Act Mode** = combo `executor`). Todo plano deve ser executado diretamente no **Act Mode** pelo `executor`, sem tentar disparar subagentes paralelos.

---

## Skills Principais (Decisão, Segurança e Memória)

| Skill | Quando usar / Gatilhos | Caminho (`SKILL.md`) |
| :--- | :--- | :--- |
| **`llm-council`** | Decisões arquiteturais com tradeoffs reais (*"council this"*, *"pressure-test this"*, *"which option"*). Não usar para perguntas simples de sim/não. | `C:\Users\Luiz Gustav\.claude\skills\llm-council\SKILL.md` |
| **`cc-safety-net`** | Diagnosticar comandos bloqueados, regras de segurança ou proteção contra comandos destrutivos (`npx -y cc-safety-net`). | `C:\Users\Luiz Gustav\.claude\skills\cc-safety-net\SKILL.md` |
| **`memory-setup`** | Configurar ou ajustar busca de memória persistente (`MEMORY.md`, logs diários e configurações de memória). | `C:\Users\Luiz Gustav\.claude\skills\memory-setup\SKILL.md` |

---

## Superpowers (Fluxo de Engenharia — Plan & Act)

| Skill | Modo / Quando usar | Caminho (`SKILL.md`) |
| :--- | :--- | :--- |
| **`using-superpowers`** | Referência inicial de uso do Superpowers (respeitando a restrição de não usar subagentes). | `C:\Users\Luiz Gustav\.claude\skills\using-superpowers\SKILL.md` |
| **`brainstorming`** | **Plan Mode:** Refinar requisitos, explorar alternativas e validar design antes de especificar. | `C:\Users\Luiz Gustav\.claude\skills\brainstorming\SKILL.md` |
| **`writing-plans`** | **Plan Mode:** Estruturar planos de implementação passo a passo (`SPEC.md`) claros para o `executor`. | `C:\Users\Luiz Gustav\.claude\skills\writing-plans\SKILL.md` |
| **`executing-plans`** | **Act Mode:** Executar um plano aprovado em lotes verificáveis, seguindo o `SPEC.md` estritamente. | `C:\Users\Luiz Gustav\.claude\skills\executing-plans\SKILL.md` |
| **`test-driven-development`** | **Act Mode:** Implementar funcionalidades ou correções seguindo ciclo TDD (teste falhando → implementação → verde). | `C:\Users\Luiz Gustav\.claude\skills\test-driven-development\SKILL.md` |
| **`systematic-debugging`** | **Plan / Act:** Investigar bugs complexos, falhas de MQTT/NFC ou testes quebrados pela causa raiz antes de corrigir. | `C:\Users\Luiz Gustav\.claude\skills\systematic-debugging\SKILL.md` |
| **`verification-before-completion`** | **Act Mode:** Antes de declarar qualquer tarefa concluída, rodar testes reais (`gradlew.bat :app:shared:testAndroidHostTest --rerun-tasks`) e validar evidências. | `C:\Users\Luiz Gustav\.claude\skills\verification-before-completion\SKILL.md` |
| **`requesting-code-review`** | **Plan Mode:** Revisar alterações contra a especificação e contratos antes de finalizar/commitar. | `C:\Users\Luiz Gustav\.claude\skills\requesting-code-review\SKILL.md` |
| **`receiving-code-review`** | **Act Mode:** Aplicar feedback de revisão de código com rigor técnico. | `C:\Users\Luiz Gustav\.claude\skills\receiving-code-review\SKILL.md` |
| **`using-git-worktrees`** | Isolar alterações arriscadas ou branches paralelas usando `git worktree` (quando solicitado). | `C:\Users\Luiz Gustav\.claude\skills\using-git-worktrees\SKILL.md` |
| **`finishing-a-development-branch`** | Encerrar uma branch de desenvolvimento (checagem final de testes, limpeza e merge/PR). | `C:\Users\Luiz Gustav\.claude\skills\finishing-a-development-branch\SKILL.md` |
| **`writing-skills`** | Criar ou refatorar arquivos `SKILL.md` seguindo boas práticas. | `C:\Users\Luiz Gustav\.claude\skills\writing-skills\SKILL.md` |

---

## Skills Auxiliares Disponíveis (Documentos e Pesquisa)

Caso o usuário peça para gerar/ler documentos, pesquisar ou criar novas skills, consulte sob demanda:

- **`docx`** (Word): `C:\Users\Luiz Gustav\.claude\skills\synced\c1498f0a-14cb-4bd7-a040-fb5041d47f9c_2a28a080-12d6-452a-bc79-9d1e9ef83b3b\docx\SKILL.md`
- **`pdf`** (PDF): `C:\Users\Luiz Gustav\.claude\skills\synced\c1498f0a-14cb-4bd7-a040-fb5041d47f9c_2a28a080-12d6-452a-bc79-9d1e9ef83b3b\pdf\SKILL.md`
- **`pptx`** (PowerPoint): `C:\Users\Luiz Gustav\.claude\skills\synced\c1498f0a-14cb-4bd7-a040-fb5041d47f9c_2a28a080-12d6-452a-bc79-9d1e9ef83b3b\pptx\SKILL.md`
- **`xlsx`** (Excel): `C:\Users\Luiz Gustav\.claude\skills\synced\c1498f0a-14cb-4bd7-a040-fb5041d47f9c_2a28a080-12d6-452a-bc79-9d1e9ef83b3b\xlsx\SKILL.md`
- **`deep-research`**: `C:\Users\Luiz Gustav\.claude\skills\synced\c1498f0a-14cb-4bd7-a040-fb5041d47f9c_2a28a080-12d6-452a-bc79-9d1e9ef83b3b\deep-research\SKILL.md`
- **`skill-creator`**: `C:\Users\Luiz Gustav\.claude\skills\synced\c1498f0a-14cb-4bd7-a040-fb5041d47f9c_2a28a080-12d6-452a-bc79-9d1e9ef83b3b\skill-creator\SKILL.md`
- **`docs`**: `C:\Users\Luiz Gustav\.claude\skills\synced\c1498f0a-14cb-4bd7-a040-fb5041d47f9c_2a28a080-12d6-452a-bc79-9d1e9ef83b3b\docs\SKILL.md`
- **`import-memory`**: `C:\Users\Luiz Gustav\.claude\skills\synced\c1498f0a-14cb-4bd7-a040-fb5041d47f9c_2a28a080-12d6-452a-bc79-9d1e9ef83b3b\import-memory\SKILL.md`

# Workflow: Revisar Alterações (`/review-diff`)

Use este workflow em **Plan Mode (`planner`)** após o `executor` concluir uma implementação, antes de fazer commit.

## Passos
1. Execute `git status` e `git diff` para listar todos os arquivos modificados e novos.
2. Confira cada alteração contra:
   - `especificacao/ESPECIFICACAO.md` (e `SPEC.md`, se existir)
   - Pureza KMP em `commonMain` (nenhum import `android.*` ou `java.*` em código compartilhado)
   - Contratos MQTT (`MqttPayloads.kt`, QoS 1, timeouts 5s ACK / 60s operação) e Poka-Yoke NFC
3. Verifique se há testes cobrindo o novo comportamento e se `gradlew.bat :app:shared:testAndroidHostTest --rerun-tasks` passou após a última edição.
4. Apresente um parecer objetivo:
   - **Aprovado** ou **Ajustes necessários** (listando arquivo, linha e correção exata para o `executor`).

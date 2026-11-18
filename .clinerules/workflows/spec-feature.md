# Workflow: Especificar Nova Funcionalidade (`/spec-feature`)

Use este workflow em **Plan Mode (`planner`)** antes de implementar qualquer nova funcionalidade ou alteração relevante.

## Passos
1. **Identifique o escopo na fonte da verdade**:
   - Leia as seções correspondentes em `especificacao/ESPECIFICACAO.md`.
   - Verifique se há cenários relacionados em `app/shared/src/commonTest/kotlin/com/uniso/wms/pickbylight/HomologacaoCenariosTest.kt`.
2. **Inspecione o código atual**:
   - Leia apenas os ViewModels, Screens, Repositórios e testes diretamente afetados (sem explorar o repositório inteiro desnecessariamente).
   - Se a tarefa envolver APIs externas (Compose, Coroutines, DataStore, Paho MQTT), consulte o MCP `context7` para confirmar assinaturas.
3. **Crie ou atualize `SPEC.md`** na raiz do projeto com:
   - **Objetivo** e referência à seção de `especificacao/ESPECIFICACAO.md`
   - **Arquivos a criar/modificar** (caminhos exatos)
   - **Contratos** (modelos em `Entities.kt`, tópicos/payloads MQTT, regras NFC/Poka-Yoke, `UiState`)
   - **Plano de execução passo a passo** (checklist numerado para o `executor` no Act Mode)
   - **Plano de testes** (novos testes unitários/homologação e comando de verificação)
4. **Pare e aguarde aprovação**: Não edite código-fonte neste workflow. Peça ao usuário para revisar o `SPEC.md` e alternar para **Act Mode** quando estiver aprovado.

# Governança Plan / Act (9Router + Cline)

Este projeto usa modelos distintos no Cline: **Plan Mode (`planner`)** para raciocínio/arquitetura e **Act Mode (`executor`)** para edição de código e testes.
**Orquestração exclusiva via Plan/Act (9Router):** Não tente disparar subagentes paralelos nem utilize as skills `subagent-driven-development` ou `dispatching-parallel-agents`.

## Regras para Plan Mode (`planner`)
1. **Leia antes de propor**: Consulte `especificacao/ESPECIFICACAO.md` e os arquivos reais envolvidos (ViewModels, repositórios, payloads, testes) antes de desenhar qualquer solução.
2. **Não edite código no Plan Mode**: Produza ou atualize o plano/especificação (ex.: `SPEC.md` na raiz ou plano no chat) com:
   - Seções da `ESPECIFICACAO.md` impactadas
   - Caminhos exatos de arquivos e símbolos a criar/alterar
   - Contratos MQTT/NFC/Domínio preservados
   - Passo a passo objetivo para o `executor`
   - Critérios de verificação e testes
3. **Documentação atualizada**: Quando a tarefa envolver APIs de bibliotecas externas (Compose Multiplatform, Navigation, Coroutines, Serialization, DataStore, Paho MQTT, Turbine), consulte o MCP `context7` (se ativo) antes de especificar assinaturas.
4. **Memória entre sessões**: Use o MCP `memory` (se ativo) para consultar ou registrar decisões de tópicos MQTT, contratos de payload e convenções do projeto.

## Regras para Act Mode (`executor`)
1. **Escopo estrito**: Implemente exatamente o que foi aprovado no plano/`SPEC.md`. Não faça refatorações não solicitadas fora do escopo.
2. **Sem alucinação de símbolos**: Verifique nomes de classes, métodos e imports nos arquivos existentes antes de editar.
3. **Preservar comentários e testes existentes**: Não remova comentários úteis nem enfraqueça asserções de testes existentes.
4. **Verificação obrigatória**: Após finalizar as edições, execute os testes unitários/homologação (`gradlew.bat :app:shared:testAndroidHostTest --rerun-tasks`) e reporte o resultado real.

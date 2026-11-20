# Workflow: Verificar Build e Testes (`/verify-build`)

Use este workflow em **Act Mode (`executioner`)** para validar que o projeto compila e todos os testes passam.

## Passos
1. Execute os testes multiplataforma no host Android com reexecução forçada:
   ```cmd
   gradlew.bat :app:shared:testAndroidHostTest --rerun-tasks
   ```
2. Caso o usuário tenha pedido validação de empacotamento Android, execute também:
   ```cmd
   gradlew.bat :app:androidApp:assembleDebug
   ```
3. Reporte exatamente:
   - Resultado final (`BUILD SUCCESSFUL` ou `BUILD FAILED`)
   - Quantidade de testes executados/falhos (ou o erro exato de compilação/teste, se houver).

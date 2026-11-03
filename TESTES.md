# Validações do EK Save Editor v0.2.1 — 03/11/2026

| Verificação | Resultado |
|---|---|
| Compilação do núcleo Kotlin com `kotlinc` 1.9.0 | PASSOU |
| Abrir o `EK.bak` original, identificar os quatro saves | PASSOU |
| Abrir o `EK_armas_pocao_todos_saves.bak` e identificar quatro saves | PASSOU |
| Editar um slot, ouro, pontos livres e itens IDs 554, 750, 5070 | PASSOU |
| Editar ouro em todos os quatro saves | PASSOU |
| Preservar conteúdo de todas as entradas não selecionadas do ZIP | PASSOU |
| Reabrir e validar conteúdo do backup exportado | PASSOU |
| Revisão do fluxo de restauração e nome de exportação `EK.bak` no código | IMPLEMENTADO, SEM TESTE ANDROID |
| Gerar APK com Android SDK/Gradle | BLOQUEADO: SDK/Gradle/rede indisponíveis |
| Instalar/executar em Android real | PENDENTE |
| Autorizar Shizuku e testar leitura/gravação na pasta `Android/data` | PENDENTE |
| Restaurar no Exiled Kingdoms após modificação | PENDENTE |
| Adicionar biblioteca pesquisável de todos os itens e poções | PRÓXIMA ETAPA, APÓS VALIDAÇÃO NO JOGO |

O resultado `PASSOU` refere-se **somente à transformação e validação local dos arquivos**, não ao funcionamento do APK, à integração com Shizuku ou à aceitação do backup pelo jogo.

## Cenários de regressão adicionados (fixture sintético)

- Edição de um save, mantendo idênticos os demais arquivos internos. **PASSOU localmente.**
- Edição de todos os saves, mantendo arquivos auxiliares. **PASSOU localmente.**
- Recusa de quantidades acima do limite definido no editor. **PASSOU localmente.**
- Recusa de dados que não são ZIP / EK.bak. **PASSOU localmente.**

Executados localmente sobre `EkBackup.kt` com Kotlin/JVM e uma substituição mínima de assertivas JUnit. O workflow do GitHub utiliza a suíte JUnit real (`:app:testDebugUnitTest`) antes de compilar o aplicativo. Ainda não houve execução no ambiente Android/Gradle.

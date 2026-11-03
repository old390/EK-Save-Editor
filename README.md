# EK Save Editor — v0.3.0

Ferramenta Android independente em português para editar **backups** do jogo Exiled Kingdoms. Não é afiliada aos criadores do jogo.

## O que mudou

- **Shizuku removido**: não há dependência, botão, permissão ou serviço Shizuku.
- Escolha um arquivo `EK.bak` pelo seletor de arquivos do Android.
- **Aplicar ouro**, **Aplicar atributos**, **Aplicar habilidades**, **Aplicar poções**, **Aplicar item personalizado**, **Adicionar cada arma**: botões independentes.
- As alterações são validadas e atualizadas no próprio editor: ouro, pontos livres e inventário por save.
- Escolha individual ou todos os saves. Cada clique aplica apenas a edição da seção escolhida aos saves selecionados, sem desfazer as alterações anteriores.
- A modificação permanece **na memória** até tocar em **Exportar EK.bak modificado**. O backup original permanece intocado.
- Opção de exportar uma cópia do original. Só é possível exportar o modificado depois de aplicar pelo menos uma alteração.

## Processo de uso

1. Dentro de Exiled Kingdoms, crie um **backup atualizado**.
2. No editor, toque em **Selecionar EK.bak** e abra o arquivo do jogo.
3. Escolha um save ou todos, digite o valor e toque em **Aplicar** na seção desejada. Confira os valores alterados ainda no aplicativo.
4. Toque em **Exportar EK.bak modificado** e salve o arquivo no Android. Guarde o arquivo original em outro lugar.
5. Coloque o EK.bak modificado no diretório `/storage/emulated/0/Android/data/net.fdgames.ek.android/files/EK.bak`. Dependendo da versão do Android, esse passo pode exigir um gerenciador de arquivos com permissões apropriadas.
6. Abra Exiled Kingdoms e use a função **restaurar backup** do próprio jogo. O editor não modifica os saves ativos automaticamente.

## Segurança e limites

- O núcleo só modifica entradas ZIP de save selecionadas. Valida integralmente o arquivo recompactado e confirma valores escritos.
- Ouro: 0 a 999.999.999. Pontos livres de atributos/habilidades: 0 a 999. Quantidades de itens: 1 a 999.
- As armas e poções são inseridas com IDs definidos, não há catálogo completo ainda.
- Valores do inventário são apresentados por ID, mesmo quando não há nomes cadastrados.
- Não há acesso automático à pasta interna do jogo, nem necessidade de root ou Shizuku para **importar e exportar**.
- É necessário testar a **restauração real** com o Exiled Kingdoms; os testes automatizados verificam formato e consistência, não a aceitação pelo jogo.

## Compilar

Android SDK 35, JDK 17, Gradle 8.9, Android Gradle Plugin 8.7.3, Kotlin Plugin 2.0.21.

`gradle :app:testDebugUnitTest :app:assembleDebug`

O APK debug será `app/build/outputs/apk/debug/app-debug.apk`.

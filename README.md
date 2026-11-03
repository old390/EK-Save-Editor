# EK Save Editor — versão 0.2.1 (código-fonte)

Editor **independente** de backups do *Exiled Kingdoms* para Android. Interface em português, arte medieval produzida para o projeto, Kotlin nativo, importação manual e integração experimental com Shizuku. Não é um aplicativo oficial do jogo.

## Fluxo correto de uso (obrigatório)

1. Abra Exiled Kingdoms e **crie um backup atualizado** utilizando a função de backup do próprio jogo.
2. Feche o jogo. O arquivo de backup conhecido é `/storage/emulated/0/Android/data/net.fdgames.ek.android/files/EK.bak`.
3. No editor, importe o `EK.bak` (pelo seletor de arquivos ou, se permitido, via Shizuku).
4. Escolha um save ou todos, selecione as modificações e **exporte o arquivo modificado com o nome `EK.bak`**.
5. **Preserve uma cópia do backup original fora da pasta do jogo.** Se usou importação manual, copie o `EK.bak` modificado para o caminho acima, substituindo apenas o arquivo de backup. Se usou o botão de preparação pelo Shizuku, o editor preservará uma cópia do antigo arquivo antes de substituí-lo.
6. **Abra Exiled Kingdoms e use a função do jogo para restaurar o backup.** A exportação, por si só, não modifica saves ativos.

**Atenção:** Shizuku executado via ADB não concede acesso universal a `Android/data`, variando conforme a versão do Android e o aparelho. O modo manual continua disponível. O editor não verifica se o jogo aceitou a restauração: isso exige um teste dentro do jogo real.

## Funcionalidades implementadas

- Lê o ZIP `EK.bak` e interpreta `saves/<n>/auto.sav`, `auto2.sav`, `game.sav` e `game2.sav` codificados em Base64.
- Exibe personagem, classe, ouro, pontos livres e capacidade do inventário.
- Edita ouro e pontos **livres** de atributos/habilidades do personagem principal.
- Adiciona itens por ID em slots livres; atalhos para Adamantite Greatsword (554), Energy Blade (750), Medipack (5070) e item personalizado.
- Aplica alterações em um ou todos os saves selecionados sem modificar os demais conteúdos internos do ZIP.
- Reabre e valida o backup gerado antes de disponibilizá-lo para gravação.
- Exporta arquivo com nome `EK.bak`; também permite salvar cópia preservada do original.
- Shizuku pode ler e **preparar o `EK.bak` para restauração pelo jogo**. O serviço **não grava saves ativos** nem os restaura automaticamente.

## Compilação

Requisitos: Android SDK 35, JDK 17, Gradle 8.9, Android Gradle Plugin 8.7.3, Kotlin Gradle Plugin 2.0.21. As bibliotecas Shizuku 13.1.5 são resolvidas pelo Maven Central.

No Android Studio, importe a pasta do projeto e execute `gradle :app:assembleDebug`. O arquivo gerado deve estar em `app/build/outputs/apk/debug/app-debug.apk`.

O workflow de GitHub Actions está em `.github/workflows/android.yml` e instala o SDK 35 e o Gradle para compilar e disponibilizar o APK como artefato de execução. Para usá-lo, crie um repositório GitHub privado, envie os arquivos (inclusive a pasta `.github`) e execute Actions > **Compilar APK Android**.

**Estado atual:** o núcleo Kotlin foi testado com backups reais, mas **não há APK compilado** nesta entrega, pois o ambiente de execução não dispõe de Android SDK/Gradle nem de rede para obtê-los. A conta GitHub conectada não apresenta um repositório acessível para executar o workflow. Não foi feito teste num dispositivo real.

## Testar o núcleo sem Android

```sh
kotlinc app/src/main/java/com/ekeditor/app/EkBackup.kt tools/VerifyEk.kt -include-runtime -d /tmp/ekverify.jar
java -jar /tmp/ekverify.jar /caminho/para/EK.bak
```

Veja `TESTES.md` para resultados e pendências.

## Limitações e próxima etapa

- O catálogo pesquisável de **todos os itens, armas, equipamentos e poções** ainda não foi integrado. Planejado após a primeira compilação e teste da restauração real. Será necessário conferir os IDs e os nomes no banco de dados da versão instalada do jogo, incluindo compatibilidade de classe e quantidades empilháveis.
- Os itens oferecidos atualmente usam IDs conhecidos, mas isso não demonstra que sejam os melhores para toda build. **ID 750 = Energy Blade** (não Nanocarbon Sword).
- Não há redistribuição de atributos já gastos, edição de seguidores nem restauração automática de saves ativos nesta versão.
- O teste fora do jogo não prova compatibilidade com todas as versões do save. A versão dos backups usados para teste é `1.3.1219`.
- Operações de inventário são limitadas por slots livres; nenhum item existente é apagado automaticamente.

Ferramenta independente; sem associação com o desenvolvedor do jogo.

## Validação automatizada do build (v0.2.1)

O workflow compila o aplicativo **somente após executar os testes unitários** de inspeção e modificação de backups sintéticos. Os testes não enviam saves reais ao GitHub. O job publica `app-debug.apk` como artefato quando a compilação e os testes passam.

Etapas manuais obrigatórias: (1) criar repositório privado GitHub, (2) enviar toda a pasta do projeto, inclusive `.github/workflows/android.yml`, (3) abrir Actions > Compilar APK Android > Run workflow. Após baixar o APK, verificar no Android a leitura e a restauração do `EK.bak` pelo jogo. O código-fonte **não é um APK**.

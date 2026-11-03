# EK Save Editor v0.5.1 — feito por old390

Editor Android independente para backups `EK.bak` do jogo *Exiled Kingdoms*. Não afiliado aos criadores do jogo.

## Uso

1. Crie um **backup atualizado dentro do jogo**. Preserve uma cópia original antes de editar.
2. Importe `EK.bak` pelo seletor de arquivos do Android. Não requer root nem Shizuku.
3. No inventário visual, toque num item para **somar quantidade**, **definir quantidade** ou **excluir um stack**. Toque num **espaço vazio** para buscar um item por nome/ID, definir a quantidade e adicioná-lo naquela célula (desde que esteja vazia em todos os saves). Se houver divergência entre saves, a ação será cancelada.
4. Some pontos de ouro, atributos ou habilidades do personagem principal e do companheiro ativo. A correção v0.5.1 reconhece que o companheiro não recebe quatro pontos iniciais: por exemplo, no nível 14, com 10 extras e 24 gastos, há 0 livres; após somar 10, há 10 livres. Os saldos reais são calculados pelo nível, extras recebidos e gastos. O custo das habilidades é consultado em uma tabela de 76 entradas derivada da wiki oficial. Habilidades não catalogadas mostram saldo indisponível, sem inventar um valor.
5. Use **Conferir valores de todos os saves**. Cada modificação aplica-se a todos os saves presentes no `EK.bak`.
6. Exporte o `EK.bak` modificado. Copie-o para `/storage/emulated/0/Android/data/net.fdgames.ek.android/files/EK.bak` e **restaure o backup pelo menu do próprio jogo**.

**Importante:** o editor altera o arquivo de backup; não edita os saves ativos diretamente. Confirme o nome exato `EK.bak` ao copiar para a pasta do jogo.

## Segurança e limitações

- Alterações são transacionais: se um save tiver inventário incompatível ou pontuação fora do intervalo, nenhum dos saves é alterado naquele comando.
- Excluir item remove o stack encontrado sem deslocar os demais itens. Quantidade é definida por stack e pode não ser válida para itens não empilháveis.
- Os botões de zeramento preservam pontos já distribuídos e habilidades aprendidas. Quando o saldo livre não puder ser zerado sem interferir nos pontos gastos ou a habilidade for desconhecida, a operação será recusada.
- A grade representa **um save de referência** com ícones simbólicos (não as imagens originais do jogo), mas cada mudança é validada em todos os saves.
- Algumas habilidades ou dados de mods podem ter custos diferentes dos da wiki; verifique no jogo.
- A versão de Android foi compilada e testada automaticamente, mas ainda requer validação da restauração num dispositivo real.

## Referências

- Progressão: https://www.exiledkingdoms.com/wiki/index.php/Level
- Custo dos níveis de habilidades: https://www.exiledkingdoms.com/wiki/index.php?title=Skill_Table
- Catálogo comunitário de itens (2021): https://steamcommunity.com/app/788270/discussions/0/2515771867786715062/

## Compilação e testes

Android SDK 35, JDK 17, Gradle 8.9. O GitHub Actions executa testes unitários e `:app:assembleDebug` e publica `app-debug.apk` como artefato.

Criado por old390: https://github.com/old390/EK-Save-Editor

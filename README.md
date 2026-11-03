# EK Save Editor v0.6.0 — feito por old390

Editor Android independente para backups `EK.bak` do jogo *Exiled Kingdoms*. Não afiliado aos criadores do jogo.

## Uso

1. Crie um **backup atualizado dentro do jogo**. Preserve uma cópia original antes de editar.
2. Importe `EK.bak` pelo seletor de arquivos do Android. Não requer root nem Shizuku.
3. No inventário visual, toque num item para **somar quantidade**, **definir quantidade**, **excluir um stack** ou **substituir o item**. Toque num **espaço vazio** para buscar um item e inseri-lo. Se o mesmo espaço estiver ocupado em outro save, o editor mostra quais itens serão substituídos e pede sua confirmação. Também é possível escolher uma posição quando a adição pelo painel encontrar algum save com inventário cheio.
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

## Correção 0.5.2 — inventários divergentes

- O save exibido na grade é apenas uma referência. Outros saves podem ter posições e itens diferentes. Por isso, a falta de espaço em **um único** save bloqueava a inserção em todos.
- Somar, definir ou excluir por ID procura o item em cada save. Se ele estiver ausente, uma edição que adiciona quantidades procura uma posição livre; a exclusão não mexe nesse save. Se não houver espaço, o editor explica qual save está cheio.
- Para **substituir** itens em um slot, o app exibe o conteúdo daquela posição **em cada save** e solicita confirmação. Nenhum item é sobrescrito silenciosamente. A troca é validada contra o estado que foi mostrado antes de escrever.
- Antes de restaurar o backup, preserve uma cópia do arquivo original. Uma substituição explicitamente confirmada pode remover itens diferentes em cada save.

## v0.6.0 — Baús da Prefeitura

- Uma seção própria abaixo do inventário com um menu para escolher **Baú 1, Baú 2, Baú 3 ou Baú 4**; o conteúdo é mostrado em uma grade de 20 espaços.
- Leitura independente de `worldContainers: vault, vault2, vault3, vault4` e dos campos `hasVault, hasVault2, hasVault3, hasVault4`, sem confundir baús e mochila.
- Toque no item para acrescentar unidades ou cópias, definir a quantidade de um item empilhável, excluir uma ocorrência específica ou substituí-lo; toque no espaço vazio para selecionar um item no catálogo.
- Os **quatro saves** são editados, com validação de que os outros baús e a mochila não foram afetados. Itens não empilháveis exigem um espaço por unidade.
- Se a posição estiver ocupada em outro save, o aplicativo mostra o conteúdo de **cada save** e só substitui mediante confirmação. Editar um baú cheio sem espaços vazios exige substituição consciente.
- O botão **Desbloquear baú** ativa o indicador daquele baú nos saves, sem pagar ouro no jogo. É uma operação separada e confirmada.
- Mantenha o `EK.bak` original seguro. O estado dos baús no jogo só muda depois de exportar e restaurar o backup modificado.

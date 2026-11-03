# EK Save Editor v0.4 — feito por old390

Editor de backups **Exiled Kingdoms**, Android, Kotlin. Independente dos criadores do jogo.

## Como usar
1. No jogo, crie um **backup atualizado**.
2. Importe **EK.bak** com o seletor de arquivos. Não requer Shizuku nem root.
3. Edite ouro; adicione armas, itens e poções por uma busca offline; some pontos livres de atributos e habilidades e, se houver companheiro ativo, edite também os pontos dele.
4. Cada aplicação altera **todos os saves** do EK.bak. A tela mostra um inventário de referência (preferência pelo auto.sav), com nomes em vez de IDs. É possível conferir espaços livres mínimos entre os saves.
5. Guarde uma cópia do backup original e exporte o **EK.bak** modificado.
6. Copie o backup para `/storage/emulated/0/Android/data/net.fdgames.ek.android/files/EK.bak`, depois **restaure pelo menu do jogo**. O editor não substitui saves ativos.

## Novidades da v0.4
- Remoção do seletor de saves: sempre modifica todos.
- Busca por nome ou ID, com **724 registros**, incluindo **45 poções e consumíveis**.
- Pontos acumulativos e botão para **zerar apenas os pontos livres de atributos**, sem apagar atributos já distribuídos.
- Campos de companheiro somente quando existe um companheiro ativo.
- Preservação da edição na rotação da tela e recuperação de sessão.
- Bloqueio preventivo da inserção de itens quando um save não tem espaço disponível.

## Catálogo
Arquivo offline `app/src/main/assets/ek_items.tsv`. Dados extraídos de uma discussão comunitária de setembro de 2021:
https://steamcommunity.com/app/788270/discussions/0/2515771867786715062/

A lista não é oficial e pode não incluir itens introduzidos posteriormente. Algumas poções do catálogo são outros tipos de consumíveis. Itens exclusivos ou não empilháveis podem exigir testes específicos no jogo.

## Segurança e desenvolvimento
O app só exporta backups, preservando os demais arquivos e os saves originais em memória. Tenha uma cópia separada antes da restauração. A compilação executa os testes automatizados `:app:testDebugUnitTest` antes de criar `app-debug.apk`.

Criado por old390 — https://github.com/old390/EK-Save-Editor

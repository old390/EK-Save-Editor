# Biblioteca de IDs — fase seguinte (após os testes reais)

Objetivo: disponibilizar busca de TODOS os itens, equipamentos e poções do Exiled Kingdoms, por nome ou ID, com filtros.

Estrutura planejada para cada registro: `id`, `nome_original`, `nome_em_portugues`, `categoria` (arma, escudo, armadura, acessório, poção, pergaminho, item geral, missão), `subcategoria`, `empilhavel`, `quantidade_maxima_validada`, `requisitos`, `versao_fonte`, `fonte` e `status_verificacao`.

Critérios de aceitação:
1. Coleta a partir dos arquivos de dados da versão relevante do jogo, com conferência cruzada com listas comunitárias.
2. IDs duplicados, ausentes, removidos ou exclusivos de versões diferentes identificados e sinalizados.
3. Campo de busca por texto e ID; filtros por categoria e compatibilidade, se verificados.
4. Itens adicionados de forma segura; estoque/quantidades respeitam o comportamento de cada item.
5. Teste de importação, edição, exportação e restauração em aparelho antes de publicar o catálogo como validado.

Fonte de referência pública inicial (ainda não integralmente auditada):
https://steamcommunity.com/app/788270/discussions/0/2515771867786715062/

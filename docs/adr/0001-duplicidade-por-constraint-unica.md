# ADR 0001 — Duplicidade garantida por constraint única

**Status:** aceito

## Contexto

O `externalId` identifica a solicitação no sistema de origem, que faz retry. Duas requisições iguais
podem chegar ao mesmo tempo, inclusive em instâncias diferentes do serviço.

## Decisão

- Constraint `UNIQUE (external_id)` no PostgreSQL é a garantia.
- O serviço faz um SELECT antes (atalho para o retry comum) e, se o INSERT violar a constraint,
  relê o registro vencedor em outra transação.
- Retry com o mesmo conteúdo → `200` com o registro existente; conteúdo diferente → `409`.

## Alternativas descartadas

- **Só "check-then-insert":** janela de corrida entre SELECT e INSERT.
- **Lock pessimista / `SERIALIZABLE`:** mais caro e ainda exigiria tratar o erro de serialização; a constraint
  resolve com uma linha de DDL.
- **Lock distribuído (Redis):** dependência extra para algo que o banco já garante.
- **`INSERT ... ON CONFLICT DO NOTHING`:** funcionaria e evitaria a exceção, mas prende o código a SQL nativo
  do PostgreSQL no caminho principal; o tratamento da exceção é portável e igualmente correto.

## Consequências

- Funciona com qualquer número de réplicas.
- A unicidade é global (não por consumidor); um consumidor que reutilize o `externalId` de outro recebe `409`.
  Se a regra de negócio mudar para "único por sistema de origem", basta trocar a constraint para
  `(client_id, external_id)`.

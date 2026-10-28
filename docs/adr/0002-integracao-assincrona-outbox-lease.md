# ADR 0002 — Integração assíncrona com a tabela como outbox e lease

**Status:** aceito

## Contexto

Após criar a solicitação é preciso chamar o provedor externo, que pode ficar lento, fora do ar ou dar timeout
(caso ambíguo: pode ter ativado). Não queremos chamada HTTP dentro de transação, nem perder solicitações
se a aplicação cair entre o commit e a chamada.

## Decisão

- O `POST` só grava a solicitação (`PENDING`, `next_attempt_at = now`) e responde `201`.
- Um dispatcher agendado, em cada ciclo:
  1. bloqueia um lote vencido com `FOR UPDATE SKIP LOCKED`, marca `PROCESSING`, incrementa `attempts`
     e define `next_attempt_at = now + lease`; commit;
  2. chama o provedor fora de transação, com `Idempotency-Key = id da solicitação`;
  3. em nova transação, aplica o resultado se a tentativa ainda for a vigente.
- Falha temporária permanece em `PROCESSING` com nova data em `next_attempt_at` (backoff exponencial com jitter
  ou `Retry-After`); ao atingir o limite, `FAILED`.

## Alternativas descartadas

- **Chamada síncrona no POST:** latência da API passa a ser a do provedor; provedor fora = API fora.
- **`@Async` / evento após commit:** perde a solicitação se o processo cair antes de enviar; precisaria de
  reconciliação separada de qualquer forma.
- **Fila (Kafka/SQS) já nesta versão:** resolve, mas acrescenta infraestrutura e o problema de gravar no banco e
  publicar na fila atomicamente (que pede um outbox de qualquer forma). A tabela já é o outbox.
- **Voltar para `PENDING` em falha temporária:** perderia a informação de que já houve tentativa (relevante no timeout).

## Consequências

- Reconciliação de solicitações presas em `PROCESSING` é automática (o lease expira) — não há job separado.
- Várias réplicas dividem o trabalho sem duplicar envios.
- Latência até a ativação inclui o intervalo de polling (1 s, configurável).
- Polling gera uma query leve por segundo por réplica, atendida por índice parcial.
- Correção da idempotência depende de o provedor honrar a `Idempotency-Key`.

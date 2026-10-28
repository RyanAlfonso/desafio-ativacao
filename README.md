# Serviço de ativação de produtos

Registra solicitações de ativação de produtos de clientes, consulta o status e ativa cada
solicitação chamando um provedor externo (`POST /provider/v1/activate`).

**Stack:** Java 21 · Spring Boot 3.5 (MVC, Data JPA/Hibernate, Security Resource Server, Actuator) ·
PostgreSQL 16 · Flyway · Maven · JUnit 5, Mockito, AssertJ, MockMvc, Testcontainers, WireMock, ArchUnit ·
Docker / Docker Compose · GitHub Actions.

---

## Como executar

Pré-requisitos: JDK 21 e Docker (para os testes de integração e o compose).

### Build e testes (um comando)

```bash
./mvnw verify
```

- `*Test` → testes unitários e de fatia (surefire, não precisam de Docker);
- `*IT` → testes de integração com PostgreSQL real via Testcontainers e provedor via WireMock (failsafe).
  Sem Docker disponível eles são **pulados** (`disabledWithoutDocker`), não quebram o build.

### Aplicação completa (app + banco + mock do provedor + OAuth fake)

```bash
docker compose up --build
```

| Serviço | Porta no host | Papel |
|---|---|---|
| `app` | 8080 | a API |
| `postgres` | 5433 | banco |
| `provider-mock` | 8089 | WireMock simulando o provedor ([mappings](docker/wiremock/mappings/activate.json)) |
| `mock-oauth2` | 8090 | servidor OAuth 2.0 de teste ([mock-oauth2-server](https://github.com/navikt/mock-oauth2-server)) que emite JWT por *client credentials* |

Obter um token (clientes configurados em [docker/mock-oauth2.json](docker/mock-oauth2.json)):

```bash
TOKEN=$(curl -s -X POST localhost:8090/default/token -d grant_type=client_credentials -d client_id=erp-writer -d client_secret=x | grep -o '"access_token" *: *"[^"]*"' | cut -d'"' -f4)
```

| client_id | escopos | uso |
|---|---|---|
| `erp-writer` | `activation:read activation:write` | cria e consulta |
| `portal-reader` | `activation:read` | só consulta (POST → 403) |
| `other-audience` | ambos, mas `aud` errado | token recusado (401) |

Criar e consultar:

```bash
curl -i -X POST localhost:8080/api/v1/requests -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"externalId":"EXT-12345","customerId":"CUST-10001","productCode":"INTERNET-500"}'
```

```bash
curl -s localhost:8080/api/v1/requests/<id> -H "Authorization: Bearer $TOKEN"
```

O mock do provedor muda de comportamento pelo `productCode`: `REJECT-*` → 400, `ERROR-*` → 500,
`UNAVAILABLE-*` → 503 com `Retry-After: 5`, `SLOW-*` → responde depois do read-timeout; qualquer outro → 200.

### Rodar só a aplicação, fora do Docker

Suba o banco e os mocks (`docker compose up postgres provider-mock mock-oauth2`) e rode
`DB_URL=jdbc:postgresql://localhost:5433/activation ./mvnw spring-boot:run`.

---

## API

| Método | Caminho | Escopo | Respostas |
|---|---|---|---|
| `POST` | `/api/v1/requests` | `activation:write` | `201` + `Location` (criada) · `200` + `Location` (retry idêntico) · `400` · `409` |
| `GET` | `/api/v1/requests/{id}` | `activation:read` | `200` · `404` (inexistente **ou** de outro consumidor) |
| `GET` | `/api/v1/requests?status=&customerId=&page=&size=` | `activation:read` | `200` paginado (`size` 1–100, padrão 20, ordem `createdAt desc`) |

Validação do corpo: `externalId`, `customerId` e `productCode` obrigatórios, até 64 caracteres,
apenas `[A-Za-z0-9._-]`. Corpo limitado a 16 KB (`413`; sem `Content-Length`, `411`). Datas em UTC, ISO-8601.
A listagem ordena por `createdAt desc, id desc` (o `id` desempata para a paginação ser estável).

Resposta:

```json
{
  "id": "0b7f3c1e-5d2a-4c4e-9a51-2f8d7b1e6c90",
  "externalId": "EXT-12345",
  "customerId": "CUST-10001",
  "productCode": "INTERNET-500",
  "status": "FAILED",
  "attempts": 1,
  "failureReason": "Provedor recusou a solicitação (HTTP 400)",
  "createdAt": "2026-10-26T14:03:11.123456Z",
  "updatedAt": "2026-10-26T14:03:12.456789Z"
}
```

`attempts` e `failureReason` (só em `FAILED`) foram acrescentados ao contrato mínimo porque, com o
processamento assíncrono, é pelo GET que o consumidor descobre o que aconteceu com a integração.

Erros seguem **Problem Details (RFC 9457)** com `traceId` (o mesmo devolvido no header `X-Correlation-Id`
e gravado nos logs):

```json
{
  "type": "https://api.example.com/problems/validation-error",
  "title": "Dados inválidos",
  "status": 400,
  "detail": "Um ou mais campos não passaram na validação.",
  "instance": "/api/v1/requests",
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
  "errors": [{ "field": "customerId", "message": "é obrigatório" }]
}
```

---

## Decisões técnicas

### Estrutura

Pacotes por responsabilidade, sem camadas além do necessário:

```
api/          controller, DTOs (records), tratamento de erros, filtros HTTP (correlação, tamanho do corpo)
application/  casos de uso: criação/consulta, dispatcher de envio, política de retry, porta do provedor
domain/       ActivationRequest (entidade + regras de transição) e RequestStatus
persistence/  repositório Spring Data e specifications da listagem
provider/     cliente HTTP do provedor (implementa a porta ActivationProvider)
config/       segurança, Clock, scheduling
```

As regras de dependência são verificadas por ArchUnit ([ArchitectureTest](src/test/java/com/example/activation/architecture/ArchitectureTest.java)):
o domínio não depende de Spring, `application` não conhece HTTP, ninguém depende de `provider` (só da porta).
A entidade JPA é a própria entidade de domínio (anotações JPA são o único acoplamento): separar em dois
modelos aqui seria cerimônia sem ganho. DTOs da API são separados da entidade.

### Estados e transições

```
PENDING ──► PROCESSING ──► COMPLETED
               │  ▲   └──► FAILED
               └──┘  (falha temporária: nova tentativa agendada)
```

- O status só muda por métodos de domínio (`startAttempt`, `complete`, `fail`, `scheduleRetry`); não há setters.
  Transição inválida (ex.: `COMPLETED → PROCESSING`) lança `InvalidStatusTransitionException`.
- **Falha temporária permanece em `PROCESSING`** com `attempts` incrementado e `next_attempt_at` agendado.
  Escolhi não voltar para `PENDING` porque `PENDING` significa "nunca enviado ao provedor"; depois de
  uma tentativa (principalmente com timeout) o provedor pode já ter ativado, e o status deve refletir
  que a solicitação está em andamento.
- `@Version` (lock otimista) protege contra escrita concorrente; o dispatcher ainda confere se a
  tentativa que terminou é a vigente antes de gravar o resultado (detalhes abaixo).

### Duplicidade (item 3)

**Garantia: constraint `UNIQUE (external_id)` no banco.** Verificar "existe?" e depois inserir não basta:
duas requisições simultâneas podem fazer o SELECT antes de qualquer uma inserir, e as duas passam.
Só o banco serializa isso de forma atômica.

Fluxo do `create` ([ActivationRequestService](src/main/java/com/example/activation/application/ActivationRequestService.java)):

1. busca pelo `externalId` (atalho para o caso comum de retry, evita um INSERT com erro);
2. se não existe, `INSERT` na própria transação;
3. se o INSERT viola a constraint (perdeu a corrida), relê o registro vencedor numa **transação nova**
   (no PostgreSQL uma transação com erro não aceita mais comandos — por isso o método não é `@Transactional`).

| Cenário | Resposta | Por quê |
|---|---|---|
| Mesmo `externalId`, mesmo conteúdo (retry legítimo) | `200 OK` com o registro existente e `Location` | Retry é esperado; o cliente recebe o mesmo resultado que receberia na primeira vez e não precisa tratar erro. `200` em vez de `201` sinaliza que nada novo foi criado. |
| Mesmo `externalId`, conteúdo diferente | `409 Conflict` (`duplicate-external-id`) | É erro do sistema de origem (reuso de identificador); devolver o registro antigo esconderia o problema. |
| Mesmo `externalId` vindo de outro consumidor | `409 Conflict` | A unicidade é global (como pede o enunciado). A mensagem não revela os dados do outro consumidor. |
| Duas requisições idênticas em paralelo | uma recebe `201`, a outra `200` com o **mesmo** id | Coberto por [ConcurrencyIT](src/test/java/com/example/activation/application/ConcurrencyIT.java) com 16 threads contra PostgreSQL real. |

"Mesmo conteúdo" = mesmo consumidor + `customerId` + `productCode`.

### Integração com o provedor (item 5)

**Assíncrona.** O `POST /api/v1/requests` grava a solicitação e responde `201 PENDING` em milissegundos;
um dispatcher envia ao provedor em segundo plano. Assim a latência e a disponibilidade da API não
dependem do provedor: se ele ficar lento ou fora, a API continua aceitando solicitações e elas são
enviadas quando ele voltar. O consumidor acompanha pelo `GET` (em produção, também por evento — ver Produção).

**Garantia de envio sem chamada HTTP dentro de transação** ([ActivationDispatcher](src/main/java/com/example/activation/application/ActivationDispatcher.java)):
a própria tabela funciona como *outbox* — a solicitação nasce com `next_attempt_at = now` na mesma transação do INSERT.
O dispatcher (a cada 1 s) faz:

1. **transação curta:** `SELECT ... WHERE status IN (PENDING, PROCESSING) AND next_attempt_at <= now FOR UPDATE SKIP LOCKED LIMIT n`,
   marca `PROCESSING`, incrementa `attempts` e reserva por um *lease* (`next_attempt_at = now + 30s`); commit;
2. **chamada HTTP fora de transação** (nenhuma conexão de banco presa esperando o provedor);
3. **transação curta:** aplica o resultado, se a tentativa ainda for a vigente (`attempts` igual e status não final).

Se a aplicação cair entre o commit e a chamada (ou durante ela), o lease expira e a solicitação volta a ser
elegível sozinha — o mesmo mecanismo que agenda retries faz a **reconciliação** de solicitações presas em
`PROCESSING`, sem job separado. Se o lease expira depois da **última** tentativa permitida, a solicitação vai
para `FAILED` sem nova chamada — o limite de tentativas vale também para quedas, não só para erros do provedor.
Uma exceção inesperada no próprio cliente HTTP (bug, configuração inválida) é tratada como falha temporária,
seguindo o mesmo backoff e limite. `SKIP LOCKED` permite várias instâncias sem enviar a mesma solicitação duas vezes
(também testado em `ConcurrencyIT`).

**Idempotência no provedor:** toda chamada envia `Idempotency-Key: <id da solicitação>` (o mesmo em todas as
tentativas). Isso resolve a ambiguidade do timeout: o provedor pode ter ativado e só a resposta se perdeu; a nova
tentativa com a mesma chave deve devolver o resultado original em vez de ativar de novo. Isso **depende do provedor
suportar a chave** — é um ponto a combinar no contrato; sem isso, a alternativa é consultar o status no provedor
antes de reenviar.

**Timeouts explícitos e configuráveis:** connect 2 s, read 5 s (`PROVIDER_CONNECT_TIMEOUT`, `PROVIDER_READ_TIMEOUT`),
no `RestClient` sobre o `HttpClient` do JDK. O lease (30 s) é maior que a soma, para não haver duas tentativas simultâneas.

| Retorno do provedor | Novo status | Nova tentativa? | Justificativa |
|---|---|---|---|
| Sucesso (2xx) | `COMPLETED` | Não | Ativação confirmada. |
| HTTP 400 / 422 / outros 4xx | `FAILED` | Não | O provedor recusou o conteúdo; repetir dá o mesmo resultado. `failureReason` explica. |
| HTTP 500 / 502 / 504 | `PROCESSING` | Sim, com backoff | Falha do lado do provedor, geralmente transitória. |
| Timeout / erro de conexão | `PROCESSING` | Sim, com backoff | Resultado desconhecido; seguro repetir por causa da `Idempotency-Key`. |
| HTTP 429 / 503 com `Retry-After` | `PROCESSING` | Sim, **no prazo pedido** (segundos ou data HTTP), limitado a `max-backoff` | O provedor disse quando voltar; respeitar evita piorar a sobrecarga. |
| HTTP 408 | `PROCESSING` | Sim | Timeout do lado do provedor. |
| Limite de tentativas atingido | `FAILED` | Não | `failureReason = "Limite de tentativas atingido: ..."`; alerta operacional. |

Backoff exponencial com *jitter* (entre 50% e 100% de `base × 2^(n-1)`; base 2 s, teto 5 min, 5 tentativas —
tudo configurável por variável de ambiente). O jitter evita que muitas solicitações que falharam juntas
voltem todas no mesmo instante.

Observação sobre 401/403 do provedor: tratei como recusa (`FAILED`), mas na prática indicam credencial
nossa errada/expirada, não problema da solicitação. Em produção eu os trataria como falha temporária
**com alerta**, para não falhar em massa por um problema de configuração.

**Circuit breaker (não implementado, de propósito):** com o processamento assíncrono, o efeito de um provedor
fora do ar já é contido — a API não sofre, e o backoff espaça as tentativas. O que um circuit breaker
(Resilience4j) acrescentaria é parar de gastar *tentativas* enquanto o provedor está comprovadamente fora:
com o circuito aberto, o dispatcher adiaria o lote sem chamar o provedor e sem incrementar `attempts`.
Seria o próximo passo se o volume crescer.

### Tratamento de erros (item 6)

[ApiExceptionHandler](src/main/java/com/example/activation/api/ApiExceptionHandler.java) estende
`ResponseEntityExceptionHandler`, então os erros do próprio Spring MVC (JSON malformado, UUID inválido,
parâmetro fora do limite, método não suportado...) também saem como Problem Details.

| Situação | HTTP |
|---|---|
| Dados inválidos (corpo, JSON malformado, UUID/enum inválido, `size` > 100) | 400 `validation-error` |
| Sem token / token inválido | 401 |
| Escopo insuficiente | 403 |
| Registro inexistente (ou de outro consumidor) | 404 |
| Duplicada com conteúdo diferente | 409 `duplicate-external-id` |
| Corpo > 16 KB / sem `Content-Length` | 413 / 411 |
| Erro inesperado | 500 com mensagem genérica + `traceId`; stack trace só no log |

**Erro de integração** não aparece como erro HTTP do POST (que é assíncrono): aparece no recurso, como
`status = FAILED` + `failureReason`, ou `PROCESSING` + `attempts` enquanto houver retry. `failureReason` é
texto gerado por nós (ex.: "HTTP 400"), nunca o corpo de resposta do provedor.

### Segurança (item 7) — implementada

- **Fluxo OAuth 2.0: Client Credentials.** Os consumidores são sistemas, não usuários; não há usuário para
  consentir nem navegador para redirecionar. Cada sistema tem seu `client_id`/segredo (ou, melhor, `private_key_jwt`/mTLS)
  no servidor de autorização (Keycloak, Entra ID, Cognito...) e recebe um JWT de vida curta.
- **O serviço é um Resource Server** (`spring-boot-starter-oauth2-resource-server`) e valida no JWT:
  assinatura (chaves públicas obtidas do **JWKS** do servidor de autorização, com cache e rotação automática por `kid`),
  emissor (`iss`), audiência (`aud = activation-api`) e validade (`exp`/`nbf`, com tolerância de relógio padrão).
  Configuração por `JWT_ISSUER_URI`, `JWT_JWK_SET_URI`, `JWT_AUDIENCE`.
- **Autorização por escopo:** `activation:write` para criar, `activation:read` para consultar
  ([SecurityConfig](src/main/java/com/example/activation/config/SecurityConfig.java)). Qualquer outra rota é negada.
- **Isolamento entre consumidores (BOLA):** a solicitação guarda o `client_id` do token que a criou; consulta por id e
  listagem filtram por ele. Pedir o id de outro consumidor devolve **404** (e não 403), para não confirmar que o id existe.
- **Limites:** corpo até 16 KB (filtro antes do parsing) e tamanho de página até 100. *Rate limiting* eu colocaria no
  API Gateway / ingress (por `client_id`), não na aplicação — é mais barato rejeitar antes de chegar ao serviço e o limite
  precisa ser global entre réplicas.
- **Credencial do provedor:** lida de variável de ambiente (`PROVIDER_API_KEY`), nunca versionada nem logada
  (o `toString` de `ProviderProperties` mascara). Em produção, vem do cofre de segredos (AWS Secrets Manager /
  Azure Key Vault / Vault) injetado no pod; rotação com período de sobreposição (provedor aceita a chave antiga e a nova
  por um tempo, troca-se o segredo, o pod recarrega). Se o provedor suportar OAuth, preferível usar client credentials
  também na saída, com token de vida curta.
- Testes de `401`/`403` sem servidor de autenticação real usando `SecurityMockMvcRequestPostProcessors.jwt()`;
  no compose, um servidor OAuth de teste emite tokens reais assinados (validação de assinatura/emissor/audiência de ponta a ponta).

### Persistência e índices

Schema versionado com Flyway ([V1](src/main/resources/db/migration/V1__create_activation_request.sql));
Hibernate só valida (`ddl-auto: validate`). Datas em `TIMESTAMPTZ` (UTC).

| Índice | Atende |
|---|---|
| `UNIQUE (external_id)` | regra de duplicidade + busca por `externalId` |
| `(client_id, status, created_at DESC)` | listagem por status (sempre filtrada pelo dono, ordenada por criação) |
| `(client_id, customer_id, created_at DESC)` | listagem por cliente |
| `(next_attempt_at) WHERE status IN ('PENDING','PROCESSING')` | dispatcher; índice **parcial** fica pequeno porque a maioria das linhas termina `COMPLETED`/`FAILED` |

A listagem sem filtro usa o prefixo `client_id` de qualquer um dos dois compostos. Se o volume crescer e o
filtro combinado `status + customerId` for comum, avaliaria `(client_id, customer_id, status, created_at)`
olhando o `pg_stat_statements` real, em vez de criar índices por antecipação.

---

## Estratégia de testes

103 testes (87 unitários/fatia + 16 de integração). A divisão segue "o que precisa de quê para provar algo":

| Tipo | Classe(s) | O que prova | Por que nesse nível |
|---|---|---|---|
| Unitário puro | `ActivationRequestTest` | transições válidas/inválidas, tabela completa de estados | regra de domínio, sem Spring: milissegundos |
| Unitário puro | `RetryPolicyTest`, `HttpActivationProviderClassifyTest` | backoff/jitter/teto; classificação de cada status HTTP e `Retry-After` | lógica pura, muitos casos |
| Unitário com Mockito | `ActivationRequestServiceTest` | retry vs. conflito, **corrida perdida no INSERT**, erro de integridade não relacionado | o caso de corrida é difícil de forçar com banco real de forma determinística |
| Unitário com Mockito | `ActivationDispatcherTest` | exceção inesperada no cliente vira retry com backoff; resultado de tentativa que não é mais a vigente é descartado; métricas | exige intercalar eventos num ponto exato da execução |
| Fatia web (`@WebMvcTest`) | `ActivationRequestControllerTest` | validação, 201/200/409/404/400/413/500, Problem Details, **401/403** | camada HTTP + segurança, serviço mockado |
| Integração (Testcontainers + WireMock) | `ActivationRequestApiIT` | fluxo completo API → PostgreSQL → dispatcher → provedor: sucesso, 400, 500 → retry → sucesso, 503 até o limite, `Retry-After`, **timeout**, conexão resetada, headers enviados (`Idempotency-Key`, correlação, credencial), isolamento entre consumidores, listagem/paginação, **reconciliação**: solicitação deixada em `PROCESSING` por uma instância que caiu é reenviada quando o lease expira, ou vai para `FAILED` se já estava na última tentativa | comportamento depende do banco real (constraint, `SKIP LOCKED`, tipos) e de HTTP real (timeouts) |
| Concorrência (Testcontainers) | `ConcurrencyIT` | 16 POSTs simultâneos com o mesmo `externalId` → **1 registro**; 16 dispatchers simultâneos → **cada solicitação enviada exatamente 1 vez** | só o PostgreSQL real prova constraint e `SKIP LOCKED` (H2 não reproduz) |
| Arquitetura | `ArchitectureTest` | regras de dependência entre pacotes | evita erosão silenciosa |

Nos testes de integração o dispatcher agendado fica desligado e cada teste chama `dispatchDue()` explicitamente:
o teste controla *quando* o envio acontece, sem `sleep` nem flakiness.

---

## Cenário de análise (item 10)

> API 300 ms → 2,5 s · CPU 45% · memória 60% · algumas queries 50 ms → 1,2 s · provedor 400 ms → 3 s

**O que investigaria primeiro.** CPU e memória normais dizem que a aplicação não está *trabalhando* mais —
está *esperando*. Então começo pelo que ela espera: conexões do pool, banco e provedor. Primeiro olho a
linha do tempo: as três degradações começaram juntas? Coincidem com deploy, migração, pico de tráfego,
job batch ou mudança no provedor?

**Aplicação, banco ou integração?**
- **Tracing distribuído** (OpenTelemetry) de requisições lentas mostra onde o tempo vai: span da query, span
  do HTTP ao provedor ou tempo *antes* deles (fila por conexão/thread).
- **Pool do Hikari** (`hikaricp_connections_pending`, `hikaricp_connections_acquire_seconds`, `..._usage_seconds`):
  se o tempo de *aquisição* de conexão subiu, a requisição está esperando conexão, não executando query.
- **Banco:** `pg_stat_statements` (quais queries pioraram e se o plano mudou), `pg_stat_activity`
  (sessões esperando lock, *idle in transaction*), locks, I/O, autovacuum/bloat, estatísticas desatualizadas.
- **Provedor:** latência/erros por status no client (`activation_provider_calls` por `outcome`), comparar com o
  status/SLA publicado pelo provedor; se outros clientes dele também sentem, o problema é dele.

**Logs/métricas úteis.** `http_server_requests` (p50/p95/p99 por rota e status), `activation_provider_calls`
(latência e taxa por outcome), métricas do Hikari, JVM (GC pause, threads), backlog do dispatcher
(solicitações com `next_attempt_at` vencido), logs com `traceId` para seguir um caso lento de ponta a ponta,
slow query log do PostgreSQL.

**Os três sintomas com causa comum — hipótese.** *Chamada ao provedor (ou outro I/O lento) dentro de transação
aberta.* Se o provedor ficou lento (400 ms → 3 s) e o código segura uma conexão de banco durante a chamada, as
conexões ficam presas 3 s cada, o pool esgota, as requisições da API passam a esperar conexão (API 2,5 s) e
as transações longas seguram locks de linha — então *algumas* queries que tocam as mesmas linhas ficam
esperando lock (50 ms → 1,2 s), sem a CPU subir. Confirmaria vendo: `connections_pending` alto e `acquire` lento
no Hikari; `pg_stat_activity` com sessões *idle in transaction* e `wait_event_type = Lock`; e, nos traces, o span
do provedor *dentro* do span da transação. (Esta solução foi desenhada justamente para evitar isso: a chamada
ao provedor nunca acontece com transação aberta.)

Outras hipóteses de causa comum: banco saturado por outro processo (job, migração, vacuum) e o "provedor lento" ser
na verdade nosso retry em massa; ou degradação de rede/DNS na infraestrutura afetando banco e provedor juntos.

**Melhorias possíveis.** Tirar I/O remoto de dentro de transação; processamento assíncrono (já feito aqui);
timeouts curtos e explícitos + circuit breaker no provedor; *bulkhead* (pool separado para o provedor, para não
consumir as threads da API); ajustar índices/planos das queries que pioraram (`EXPLAIN (ANALYZE, BUFFERS)`);
dimensionar o pool de conexões pelo que o banco aguenta; cache de leitura se houver consultas repetidas.

**Validar a correção em produção.** Definir antes o critério (ex.: p95 da API < 400 ms, p95 das queries de volta
a ~50 ms, `connections_pending` ≈ 0). Publicar em canário / parte das réplicas e comparar as mesmas métricas entre
versão nova e antiga no mesmo período; acompanhar por um ciclo de tráfego completo (inclusive pico); manter os
alertas de SLO e o rollback prontos. E reproduzir o cenário em teste de carga com o provedor simulado lento
(WireMock com `fixedDelay`) para garantir que a regressão não volta.

---

## Produção

### Como executaria

- Imagem gerada pelo [Dockerfile](Dockerfile): *multi-stage*, JRE 21 Alpine, camadas do Spring Boot
  (dependências separadas da aplicação → push/pull menores), usuário não-root, heap proporcional ao limite do contêiner
  (`-XX:MaxRAMPercentage=75`), `ExitOnOutOfMemoryError` (falha rápido para o orquestrador reiniciar), `HEALTHCHECK`.
- Toda configuração por variável de ambiente (`DB_*`, `JWT_*`, `PROVIDER_*`, `DISPATCH_*`); segredos (`DB_PASSWORD`,
  `PROVIDER_API_KEY`) injetados do cofre de segredos do provedor cloud, nunca na imagem.
- Em Kubernetes (ou ECS): ≥ 2 réplicas, `readinessProbe` em `/actuator/health/readiness`, `livenessProbe` em
  `/actuator/health/liveness`, requests/limits de CPU e memória, `server.shutdown=graceful` + `terminationGracePeriodSeconds`
  para terminar requisições em andamento; o agendador também espera o ciclo do dispatcher em curso terminar
  (`spring.task.scheduling.shutdown.await-termination`). Uma solicitação que estava sendo enviada quando o pod morreu
  é retomada pelo lease.
- Atrás do ingress/load balancer, `server.forward-headers-strategy=native` faz o header `Location` usar o host e o
  esquema públicos (`X-Forwarded-*`).
- Banco gerenciado (RDS/Cloud SQL/Azure Database) com backup e réplica; Flyway roda no startup (ou como job de
  migração separado antes do rollout, se houver migrações pesadas).
- Logs estruturados em JSON (`LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs`, já usado no compose) com `traceId` em cada linha;
  métricas em `/actuator/prometheus` (expostas só na rede interna / porta de management).

### Microsserviços e cloud

- **Várias instâncias:** já suportado. A duplicidade é garantida pela constraint do banco (não por memória da instância)
  e o dispatcher usa `FOR UPDATE SKIP LOCKED`, então N réplicas dividem o trabalho sem enviar a mesma solicitação duas vezes.
- **Configuração e segredos:** variáveis de ambiente / ConfigMap para configuração; Secrets Manager / Key Vault / Vault
  (via CSI driver ou External Secrets) para segredos, com rotação.
- **Avisar outros serviços:** publicar `ActivationCompleted` / `ActivationFailed` em um tópico (Kafka, SNS/SQS, Service Bus).
  Para não perder evento nem publicar evento de algo que não foi gravado, usaria uma tabela `outbox_event` gravada na mesma
  transação da mudança de status, publicada por um relay (o mesmo padrão do dispatcher, ou Debezium/CDC). Consumidores
  tratam o evento de forma idempotente (o id da solicitação é a chave).
- **CI/CD:** o [workflow](.github/workflows/ci.yml) já faz build + testes unitários + integração com Testcontainers + build da
  imagem. Evoluindo: scan de dependências e da imagem (Dependabot/Trivy), push da imagem com tag do commit para o registry,
  deploy automático em homologação (Helm/Argo CD), smoke test, e promoção para produção com canário e rollback automático por SLO.

---

## Pontos pendentes

| Item | Como faria |
|---|---|
| Tracing distribuído (OpenTelemetry) | `micrometer-tracing-bridge-otel` + exporter OTLP; o `traceId` atual seria substituído pelo do W3C `traceparent`, propagado automaticamente ao `RestClient`. Hoje a correlação é feita por `X-Correlation-Id`, gravado com a solicitação e reenviado ao provedor. |
| Gauge de solicitações por status | métrica periódica (`SELECT status, count(*) GROUP BY status`) ou derivada de eventos; hoje há o contador de transições `activation_requests_transitions_total`. |
| Circuit breaker | Resilience4j em volta do cliente do provedor, com o dispatcher adiando o lote sem consumir tentativa enquanto aberto. |
| Rate limiting | no gateway/ingress, por `client_id`. |
| Envio paralelo dentro do lote | hoje o dispatcher envia o lote em sequência (um provedor lento atrasa o lote; outras réplicas continuam). Com virtual threads e um semáforo limitando a concorrência ao provedor, o lote seria enviado em paralelo. |
| Reprocessamento manual de `FAILED` | endpoint administrativo (escopo próprio) para recolocar em `PENDING` após correção. |
| Webhook / evento para o consumidor | ver "Avisar outros serviços". |

## Melhorias futuras

- Publicar eventos de conclusão com outbox transacional.
- Contrato OpenAPI (springdoc) publicado e testes de contrato com o provedor (Pact / Spring Cloud Contract).
- Limpeza/arquivamento de solicitações antigas e particionamento por data se o volume crescer.
- Alertas: taxa de `FAILED`, backlog vencido do dispatcher, p95 do provedor, circuito aberto.
- Teste de carga (Gatling/k6) com provedor lento para calibrar pool, lote e timeouts.

## Uso de IA

Usei o Claude Code (assistente de IA da Anthropic) como par de programação durante todo o desafio: geração
do esqueleto do projeto e dos testes, redação do README/ADRs e revisão de casos de borda. Todas as decisões
de design (assíncrono com lease, permanecer em `PROCESSING` na falha temporária, `200` para retry idêntico,
404 para recurso de outro consumidor) foram revisadas e validadas por mim, e o comportamento foi verificado
pelos testes e manualmente no `docker compose`.

## Registros de decisão

- [ADR 0001 — Duplicidade garantida por constraint única](docs/adr/0001-duplicidade-por-constraint-unica.md)
- [ADR 0002 — Integração assíncrona com a tabela como outbox e lease](docs/adr/0002-integracao-assincrona-outbox-lease.md)
- [ADR 0003 — Resource server JWT com escopos e isolamento por consumidor](docs/adr/0003-seguranca-resource-server-jwt.md)

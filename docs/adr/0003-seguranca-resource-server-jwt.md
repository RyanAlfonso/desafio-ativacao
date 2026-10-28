# ADR 0003 — Resource server JWT com escopos e isolamento por consumidor

**Status:** aceito

## Contexto

A API é consumida por outros sistemas. Precisamos autenticar o sistema chamador, limitar o que ele pode
fazer e impedir que um sistema leia solicitações de outro (OWASP API1:2023 — BOLA).

## Decisão

- OAuth 2.0 **client credentials**; o serviço é um resource server Spring Security que valida JWT
  (assinatura via JWKS, `iss`, `aud = activation-api`, `exp`/`nbf`).
- Escopos: `activation:write` para criar, `activation:read` para consultar.
- O `client_id` do token é gravado como dono da solicitação; consultas filtram por ele e respondem `404`
  para solicitações de outro dono.

## Alternativas descartadas

- **API key estática:** sem expiração, rotação manual, sem escopos padronizados.
- **mTLS apenas:** autentica bem o sistema, mas não carrega escopos; pode complementar o JWT (token vinculado a certificado).
- **403 para recurso de outro dono:** confirmaria a existência do id.

## Consequências

- Nenhum segredo de validação no serviço (só chaves públicas do JWKS, com rotação automática).
- Testes de 401/403 sem servidor de autenticação (post-processor `jwt()`), e um servidor OAuth fake no compose
  para validação de ponta a ponta.

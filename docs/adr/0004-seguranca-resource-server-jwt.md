# ADR 0004 — Segurança: OAuth 2.0 Client Credentials e Resource Server JWT

**Status:** aceito

## Contexto

A API é consumida por outros sistemas, não por usuários. Não é necessário criar um servidor de
autorização.

## Decisão

- **Fluxo:** Client Credentials. Cada sistema consumidor tem seu `client_id`/segredo (ou chave privada,
  `private_key_jwt`) no servidor de autorização corporativo e obtém um JWT de curta duração. Não há
  usuário nem redirecionamento, então Authorization Code não se aplica.
- **Validação do JWT** (Spring Security Resource Server): assinatura com as chaves públicas do JWKS do
  emissor (cacheadas e renovadas na rotação), `iss` igual ao emissor configurado, `aud` contendo
  `activation-service`, `exp`/`nbf` válidos.
- **Autorização por escopo:** `activation:write` cria; `activation:read` consulta. Um sistema de
  relatórios recebe só `read`.
- **Isolamento entre consumidores (BOLA):** a solicitação guarda o consumidor que a criou (`client_id`,
  `azp` ou `sub` do token). Consultas filtram por ele; o `id` de outro consumidor retorna `404`, sem
  revelar que existe.
- **Erros:** `401` com `WWW-Authenticate` e `403`, ambos em Problem Details.
- **Credencial do provedor externo:** vem de variável de ambiente (`PROVIDER_API_KEY`), injetada a partir
  do serviço de segredos da cloud (AWS Secrets Manager, Azure Key Vault, GCP Secret Manager). Rotação:
  o provedor aceita duas chaves por um período; a nova é publicada no cofre e as instâncias são
  reiniciadas (rolling) ou relêem o segredo; depois a antiga é revogada. Nunca é logada.

## Consequências

- Localmente, o `docker compose` sobe um servidor de autorização de teste (`mock-oauth2-server`).
- Os testes usam o suporte do `spring-security-test` (`jwt()`), sem servidor de autorização real.
- Limite de taxa fica no API Gateway (por `client_id`), não na aplicação: um limite em memória não
  funciona com várias instâncias.

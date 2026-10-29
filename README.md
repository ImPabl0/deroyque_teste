# Serviço de Ativação de Produtos

Solução para o **Desafio Técnico Java Developer Pleno/Sênior** (processo seletivo conduzido pela Deroyque IT Consulting Services).

Serviço que recebe solicitações de ativação de produtos de clientes, registra as solicitações, permite consultar o status e realiza a ativação por meio de um provedor externo.

---

## Sumário

- [Stack](#stack)
- [Como executar](#como-executar)
- [API](#api)
- [Escopo do desafio](#escopo-do-desafio)
- [Decisões técnicas](#decisões-técnicas)
- [Estratégia de testes](#estratégia-de-testes)
- [Cenário de análise](#cenário-de-análise)
- [Produção](#produção)
- [Pontos pendentes](#pontos-pendentes)
- [Melhorias futuras](#melhorias-futuras)
- [Uso de IA](#uso-de-ia)

---

## Stack

| Item | Escolha |
|---|---|
| Linguagem | Java 21 (records, sealed interfaces, `switch` com pattern matching, threads virtuais) |
| Framework | Spring Boot 4.1 (Spring MVC, Spring Data JPA/Hibernate, Spring Security Resource Server, Actuator) |
| Build | Maven (wrapper incluso: `./mvnw verify` compila e roda todos os testes) |
| Banco | PostgreSQL 17 (H2 em modo PostgreSQL nos testes rápidos) |
| Migrações | Flyway (`src/main/resources/db/migration`) |
| Cliente HTTP | `RestClient` sobre o `HttpClient` do JDK, com timeouts explícitos |
| Testes | JUnit 5, Mockito, AssertJ, MockMvc, WireMock, Awaitility, Testcontainers, ArchUnit |
| Ambiente | Docker + Docker Compose (app + banco + mock do provedor + servidor OAuth de teste) |
| CI | GitHub Actions (`.github/workflows/ci.yml`) |

### Estrutura

```
src/main/java/br/teste/deroyque
├── advices          # @RestControllerAdvice: exceções → Problem Details
├── clients          # cliente HTTP do provedor externo (implementa ProviderGateway)
├── config           # segurança, scheduling, Clock, @ConfigurationProperties, filtros web
├── controllers      # controller REST
├── dtos             # records de entrada/saída e comandos
├── exceptions       # exceções de negócio
├── models           # entidade ActivationRequest e suas transições
│   └── enums        # ActivationStatus
├── repositories     # repositórios Spring Data
├── responses        # tipos dos Problem Details
├── security         # identidade do consumidor a partir do JWT
├── services         # casos de uso, processador, política de retentativa, porta do provedor
│   └── scheduler    # dispatcher agendado (outbox)
└── utils            # correlation id
```

As regras de dependência entre essas camadas são verificadas pelo `ArchitectureTest` (ArchUnit).

---

## Como executar

### Pré-requisitos

- Docker e Docker Compose **ou**
- JDK 21 + um PostgreSQL acessível

### 1. Variáveis de ambiente

```bash
cp .env.example .env
# ajuste os valores se necessário (principalmente POSTGRES_PASSWORD)
```

| Variável | Descrição | Padrão |
|---|---|---|
| `POSTGRES_DB` | Nome do banco | `deroyque` |
| `POSTGRES_USER` | Usuário do banco | `deroyque` |
| `POSTGRES_PASSWORD` | Senha do banco | `change-me` |
| `POSTGRES_PORT` | Porta do Postgres exposta no host | `5432` |
| `APP_PORT` | Porta da aplicação exposta no host | `8080` |
| `PROVIDER_PORT` | Porta do mock do provedor no host | `8089` |
| `OAUTH_PORT` | Porta do servidor OAuth de teste no host | `8090` |
| `PROVIDER_API_KEY` | Credencial enviada ao provedor (`X-Api-Key`) | vazio |
| `LOG_FORMAT` | `ecs` para logs JSON; vazio para texto | `ecs` |
| `JAVA_OPTS` | Opções da JVM | `-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError` |

Outras configurações da aplicação (todas com padrão em `application.properties`):

| Variável | Descrição | Padrão |
|---|---|---|
| `PROVIDER_BASE_URL` | URL do provedor | `http://localhost:8089` |
| `PROVIDER_CONNECT_TIMEOUT` / `PROVIDER_READ_TIMEOUT` | Timeouts do cliente HTTP | `2s` / `5s` |
| `ACTIVATION_MAX_ATTEMPTS` | Tentativas antes de `FAILED` | `5` |
| `ACTIVATION_INITIAL_BACKOFF` / `ACTIVATION_MAX_BACKOFF` | Backoff exponencial | `2s` / `5m` |
| `OAUTH_JWK_SET_URI` | JWKS do servidor de autorização | `http://localhost:8090/default/jwks` |
| `OAUTH_ISSUER_URI` | Emissor esperado (`iss`) | `http://localhost:8090/default` |
| `OAUTH_AUDIENCE` | Audiência esperada (`aud`) | `activation-service` |
| `DB_POOL_SIZE` | Tamanho do pool de conexões | `10` |

### 2. Subir tudo com Docker Compose

```bash
docker compose up -d --build
```

Sobe quatro contêineres:

| Serviço | Endereço no host | Papel |
|---|---|---|
| `app` | `http://localhost:8080` | a aplicação |
| `db` | `localhost:5432` | PostgreSQL |
| `provider-mock` | `http://localhost:8089` | WireMock simulando `POST /provider/v1/activate` |
| `oauth` | `http://localhost:8090` | servidor de autorização de teste (emite JWT) |

```bash
docker compose logs -f app   # acompanhar logs
docker compose down          # parar
docker compose down -v       # parar e apagar os dados do banco
```

### 3. Testar a API manualmente

Obter um token (Client Credentials). Dois clientes estão configurados em `docker/oauth/oauth.json`:
`erp-system` (leitura e escrita) e `reporting-system` (só leitura).

```bash
TOKEN=$(curl -s -X POST http://localhost:8090/default/token \
  -d grant_type=client_credentials -d client_id=erp-system -d client_secret=any \
  -d scope="activation:read activation:write" | jq -r .access_token)
```

> O token deve ser pedido em `localhost:8090`: o emissor (`iss`) do token é o endereço usado na requisição, e a aplicação espera `http://localhost:8090/default`.

Criar e consultar:

```bash
curl -i -X POST http://localhost:8080/api/v1/requests \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"externalId":"EXT-12345","customerId":"CUST-10001","productCode":"INTERNET-500"}'

curl -s http://localhost:8080/api/v1/requests/<id> -H "Authorization: Bearer $TOKEN"
curl -s "http://localhost:8080/api/v1/requests?status=COMPLETED&page=0&size=20" -H "Authorization: Bearer $TOKEN"
```

O mock do provedor responde conforme o prefixo do `productCode`, para exercitar cada cenário:

| `productCode` começa com | Resposta do mock | Status final esperado |
|---|---|---|
| qualquer outro (ex.: `INTERNET-500`) | `200` | `COMPLETED` |
| `INVALID` | `400` | `FAILED` sem retentativa |
| `ERROR` | `500` | retentativas com backoff, depois `FAILED` |
| `BUSY` | `503` + `Retry-After: 10` | retentativas a cada ≥ 10 s, depois `FAILED` |
| `SLOW` | `200` após 8 s (> timeout de 5 s) | retentativas por timeout, depois `FAILED` |

### 4. Rodar a aplicação localmente (fora do Docker)

```bash
docker compose up -d db provider-mock oauth
./mvnw spring-boot:run
```

Sem variáveis definidas, a aplicação usa `jdbc:postgresql://localhost:5432/deroyque` (usuário/senha `deroyque`), o provedor em `localhost:8089` e o OAuth em `localhost:8090`.

### 5. Testes

```bash
./mvnw verify
```

Roda todos os testes. Os testes que usam Testcontainers (PostgreSQL real) são pulados automaticamente quando não há Docker disponível; os demais usam H2 e WireMock embarcados e não precisam de nada instalado além do JDK.

---

## API

| Método | Caminho | Escopo | Respostas |
|---|---|---|---|
| `POST` | `/api/v1/requests` | `activation:write` | `201` criada · `200` repetição idêntica · `400` · `401` · `403` · `409` · `413` |
| `GET` | `/api/v1/requests/{id}` | `activation:read` | `200` · `400` (id inválido) · `401` · `403` · `404` |
| `GET` | `/api/v1/requests?status=&customerId=&page=&size=` | `activation:read` | `200` paginado · `400` |
| `GET` | `/actuator/health` | público | `200` `UP` quando a aplicação e o banco estão disponíveis |

**Validação do corpo do POST:** `externalId`, `customerId` e `productCode` são obrigatórios, com no máximo 64 caracteres, apenas letras, dígitos, `.`, `-` e `_`. Corpo limitado a 16 KB (`413` acima disso).

**Paginação:** `page` ≥ 0 (padrão 0), `size` entre 1 e 100 (padrão 20), ordenado por `createdAt` decrescente. A resposta é `{content, page, size, totalElements, totalPages}`.

**Datas:** sempre UTC, ISO-8601 (`2026-10-26T14:03:11Z`).

**Correlação:** o header `X-Correlation-Id` é aceito (8–64 caracteres alfanuméricos ou `-`) ou gerado, devolvido na resposta, gravado com a solicitação e enviado ao provedor.

**Erros:** sempre `application/problem+json` (RFC 9457), com `traceId` igual ao `X-Correlation-Id`:

```json
{
  "type": "https://api.deroyque.example/problems/validation-error",
  "title": "Dados inválidos",
  "status": 400,
  "detail": "Um ou mais campos não passaram na validação.",
  "instance": "/api/v1/requests",
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
  "errors": [ { "field": "customerId", "message": "é obrigatório" } ]
}
```

---

## Escopo do desafio

Legenda: ✅ feito · 📝 descrito no README/ADR

### Obrigatórios

| # | Item | Status |
|---|---|---|
| 1 | Java 17/21, Spring Boot, Maven, banco relacional | ✅ |
| 2 | `POST /api/v1/requests` com validação | ✅ |
| 2 | `GET /api/v1/requests/{id}` | ✅ |
| 3 | Estratégia contra `externalId` duplicado (inclusive concorrente) | ✅ |
| 4 | Estados `PENDING → PROCESSING → COMPLETED / FAILED` | ✅ |
| 5 | Integração com provedor externo (`POST /provider/v1/activate`) e comportamento por retorno | ✅ |
| 6 | Tratamento de erros sem expor detalhes internos | ✅ |
| 7 | Segurança com Spring Security / JWT / OAuth 2.0 | ✅ implementado |
| 8 | Testes: criação, duplicidade, consulta, integração | ✅ |
| 10 | Cenário de análise de performance | 📝 |
| 11 | Dockerfile + como executar em produção | ✅ |

### Diferenciais

| Item | Status |
|---|---|
| Records para DTOs, sealed + `switch` com pattern matching | ✅ (`ProviderResult`) |
| Spring Boot 3.x+ | ✅ (4.1) |
| Build e testes com um comando (`./mvnw verify`) | ✅ |
| Migrações versionadas (Flyway) | ✅ |
| Docker Compose com app + banco + mock do provedor | ✅ (+ servidor OAuth de teste) |
| Dockerfile multi-stage, imagem enxuta, usuário não-root, memória da JVM ajustada ao contêiner | ✅ |
| Desligamento gracioso e health check | ✅ |
| `201` com `Location`, validação documentada, datas UTC ISO-8601, DTOs separados da entidade | ✅ |
| Listagem paginada com filtros (e índices justificados) | ✅ |
| Transições controladas pelo domínio | ✅ |
| Lock otimista (`@Version`) | ✅ |
| Semântica de resposta para repetição (200 / 409) | ✅ |
| Envio assíncrono, sem chamada HTTP dentro de transação, garantia de envio (outbox) | ✅ |
| Timeouts configuráveis, chave de idempotência, backoff com jitter, limite de tentativas | ✅ |
| `429`/`503` com `Retry-After` | ✅ |
| Reconciliação de solicitações presas em `PROCESSING` | ✅ |
| Circuit breaker | 📝 ([Melhorias futuras](#melhorias-futuras)) |
| Problem Details (RFC 9457) com traceId | ✅ |
| Resource Server com escopos, testes 401/403 | ✅ |
| Isolamento entre consumidores (BOLA) | ✅ |
| Limite de tamanho do corpo | ✅ |
| Limite de taxa de chamadas | 📝 (API Gateway) |
| Testcontainers (concorrência no PostgreSQL real), WireMock, ArchUnit, Awaitility | ✅ |
| Teste de timeout do provedor | ✅ |
| Testes de domínio sem Spring | ✅ |
| Actuator (health check), logs com correlation id até o provedor | ✅ |
| Logs estruturados em JSON | ✅ (`LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs`) |
| Tracing distribuído (OpenTelemetry) | 📝 |
| Pipeline de CI (GitHub Actions) | ✅ |
| ADRs em `/docs/adr` | ✅ |

---

## Decisões técnicas

Os registros completos estão em [`docs/adr`](docs/adr). Resumo:

### Ambiente e configuração

- **Toda configuração sensível vem de variáveis de ambiente** (`.env` local, nunca versionado; `.env.example` como modelo). Em produção, as mesmas variáveis seriam injetadas por um serviço de segredos.
- **`spring.jpa.hibernate.ddl-auto=validate`**: o schema é responsabilidade do Flyway; o Hibernate apenas valida que as entidades batem com o banco.
- **`spring.jpa.open-in-view=false`**: evita consultas lazy disparadas fora da camada de serviço e conexões presas durante a renderização da resposta.
- **Dockerfile multi-stage**: o estágio de build usa a imagem Maven; o de runtime usa apenas o JRE Alpine, roda com usuário não-root, ajusta o heap via `-XX:MaxRAMPercentage` ao limite de memória do contêiner e tem `HEALTHCHECK` no `/actuator/health`.
- **Compose** só sobe a aplicação depois que o Postgres passa no health check (`pg_isready`).
- **Arquitetura simples em camadas** (api → application → domain, com o cliente do provedor atrás de uma interface). A entidade JPA é o modelo de domínio: separar entidade e modelo de domínio dobraria o código sem ganho real neste tamanho de serviço. Os DTOs da API são separados da entidade.
- **`Clock` injetado**: todas as datas vêm de um `Clock` UTC, o que torna os testes de domínio e retentativa determinísticos.

### Duplicidade (item 3)

Detalhes em [ADR 0001](docs/adr/0001-duplicidade-por-constraint-unica.md).

- **Garantia:** constraint `UNIQUE (external_id)` no banco. Verificar se existe e depois inserir não basta: duas requisições simultâneas podem passar pela verificação antes de qualquer uma inserir (*check-then-act*). Só o banco consegue serializar os dois `INSERT`s, inclusive com várias instâncias da aplicação.
- **Fluxo:** consulta prévia por `externalId` (atende o retry comum sem erro no banco) → `INSERT` em transação curta → se a constraint for violada, lê o registro vencedor e trata como repetição. O método de criação não é transacional de propósito: a leitura do vencedor precisa acontecer depois do rollback da transação que falhou.

| Cenário | Resposta | Justificativa |
|---|---|---|
| Mesmo `externalId`, mesmo conteúdo (retry legítimo) | `200 OK` com o registro existente | Torna o POST idempotente: o sistema de origem recebe o mesmo `id` e não precisa interpretar um erro como "talvez tenha dado certo". `200` em vez de `201` sinaliza que nada novo foi criado. |
| Mesmo `externalId`, conteúdo diferente | `409 Conflict`, "Já existe uma solicitação com o externalId 'X' e conteúdo diferente." | É um erro do chamador (reuso de identificador); aceitar silenciosamente esconderia o problema. |
| Mesmo `externalId` vindo de outro consumidor | `409 Conflict` com a mesma mensagem | Não revela dados do outro consumidor. |
| Duas requisições idênticas em paralelo | Uma recebe `201`, a outra `200` com o mesmo `id` | Coberto pelo teste concorrente (16 threads) em H2 e em PostgreSQL. |

### Estados e transições (item 4)

```
PENDING → PROCESSING → COMPLETED
              │      → FAILED
              └──────→ PENDING   (falha temporária, nova tentativa agendada)
```

- O status só muda por métodos da entidade (`startProcessing`, `complete`, `fail`, `scheduleRetry`), que rejeitam transições inválidas com `InvalidStatusTransitionException`. Não existem setters.
- **Falha temporária volta para `PENDING`**, com contador `attempts` e `next_attempt_at`. Assim `PROCESSING` significa sempre "há uma chamada em andamento agora", e uma solicitação parada em `PROCESSING` por mais de 2 min indica que a instância caiu durante a chamada (é devolvida para `PENDING` pela reconciliação).
- **Escrita concorrente:** `@Version` (lock otimista). Se duas instâncias tentarem reivindicar a mesma solicitação, só uma consegue; a outra desiste sem chamar o provedor.

### Integração com o provedor (item 5)

Detalhes em [ADR 0002](docs/adr/0002-processamento-assincrono-com-outbox.md) e [ADR 0003](docs/adr/0003-retentativas-e-transicoes-de-status.md).

| Retorno do provedor | Novo status | Nova tentativa? | Justificativa |
|---|---|---|---|
| Sucesso (2xx) | `COMPLETED` | Não | Produto ativado. |
| HTTP 400 (e demais 4xx, exceto 408/429) | `FAILED` | Não | A requisição é inválida para o provedor; repetir dá o mesmo resultado. O motivo fica em `last_error`. |
| HTTP 500 (e 502, 504, 408) | `PENDING` → `FAILED` ao esgotar | Sim, backoff exponencial com jitter, até 5 tentativas | Falha do lado do provedor, provavelmente passageira. |
| Timeout / erro de conexão | `PENDING` → `FAILED` ao esgotar | Sim, com a **mesma** `Idempotency-Key` | Resultado ambíguo: o provedor pode ter ativado. A chave de idempotência permite que ele reconheça a repetição. |
| HTTP 429 / 503 com `Retry-After` | `PENDING` → `FAILED` ao esgotar | Sim, após `max(Retry-After, backoff)` | O provedor disse quando voltar; respeitar evita agravar a sobrecarga. Aceita segundos ou data HTTP. |

- **Assíncrono:** o `POST` grava `PENDING` e responde `201` imediatamente. Se o provedor ficar lento (400 ms → 3 s), a API não é afetada; o consumidor acompanha o status via `GET`.
- **Sem chamada HTTP dentro de transação:** o processamento tem três passos independentes — transação curta para reivindicar (`PENDING → PROCESSING`), chamada HTTP sem transação, transação curta para gravar o resultado. Nenhuma conexão de banco fica presa esperando o provedor.
- **Garantia de envio (outbox):** a própria tabela é a fila. Se a aplicação cair entre o commit e a chamada, a solicitação continua `PENDING` e é enviada no próximo ciclo do dispatcher (a cada 1 s), por qualquer instância.
- **Timeouts explícitos:** conexão 2 s, leitura 5 s, configuráveis por variável de ambiente.
- **Limite de tentativas:** 5 tentativas; depois vai para `FAILED` com "Max attempts reached. Last error: ...". Backoff: 2 s, 4 s, 8 s... (teto 5 min), metade fixa e metade aleatória.
- **Reconciliação:** a cada 30 s, solicitações em `PROCESSING` há mais de 2 min voltam para `PENDING` (contam como tentativa).
- **Circuit breaker:** não implementado. Com o backoff com jitter e o limite de tentativas, um provedor fora do ar recebe poucas chamadas. Ver [Melhorias futuras](#melhorias-futuras).

### Tratamento de erros (item 6)

- `@RestControllerAdvice` estendendo `ResponseEntityExceptionHandler`: todos os erros saem como Problem Details com `traceId`.
- Dados inválidos → `400` com lista `errors`; JSON malformado → `400`; id inválido → `400`; inexistente → `404`; duplicado → `409`; corpo grande → `413`; erro de integração → `502`; inesperado → `500` com mensagem genérica.
- A exceção completa vai para o log (com o mesmo `correlationId`); o consumidor nunca vê stack trace nem mensagem interna.
- Erros do provedor no fluxo assíncrono não viram erro HTTP para o consumidor: viram o status `FAILED` da solicitação.

### Segurança (item 7)

Implementado. Detalhes em [ADR 0004](docs/adr/0004-seguranca-resource-server-jwt.md).

- **Fluxo OAuth 2.0:** Client Credentials — comunicação entre sistemas, sem usuário. Cada consumidor tem seu `client_id` no servidor de autorização corporativo.
- **Validação do JWT:** Spring Security Resource Server valida assinatura com as chaves do JWKS do emissor (`OAUTH_JWK_SET_URI`, cacheado e renovado na rotação de chaves), emissor (`iss`), audiência (`aud = activation-service`) e expiração.
- **Escopos:** `activation:write` para criar, `activation:read` para consultar.
- **Isolamento (BOLA):** cada solicitação guarda o consumidor que a criou (claim `client_id`, `azp` ou `sub`). Consultas filtram por ele; o `id` de outro consumidor retorna `404`.
- **Limites:** corpo da requisição até 16 KB. Limite de taxa fica no API Gateway, por `client_id` (um limite em memória não funciona com várias instâncias).
- **Credencial do provedor:** `PROVIDER_API_KEY`, injetada do serviço de segredos da cloud; rotação com período em que o provedor aceita a chave antiga e a nova; nunca aparece em log (`toString` mascarado).

### Índices (listagem paginada)

| Índice | Atende |
|---|---|
| `UNIQUE (external_id)` | Duplicidade e busca por `externalId` na criação |
| `(client_id, status, created_at)` | Listagem do consumidor filtrada por status, já na ordem de `created_at` |
| `(client_id, customer_id, created_at)` | Listagem do consumidor filtrada por cliente final |
| `(status, next_attempt_at)` | Dispatcher: `PENDING` vencidas e `PROCESSING` presas |

Toda listagem filtra por `client_id` (isolamento), por isso ele é a primeira coluna. Em PostgreSQL, o último poderia ser um índice parcial (`WHERE status IN ('PENDING','PROCESSING')`), menor porque a maioria das linhas estará finalizada; ficou completo para manter a migração compatível com o H2 dos testes.

### Observabilidade

- **Health check:** `/actuator/health` (inclui o banco), usado pelo `HEALTHCHECK` do Dockerfile. O Actuator é usado só para isso; a coleta de métricas foi deixada de fora de propósito.
- **Correlation id:** `X-Correlation-Id` no MDC de todos os logs da requisição, gravado na solicitação e restaurado no MDC quando o dispatcher chama o provedor, que recebe o mesmo header. Um mesmo id liga a requisição original, o processamento assíncrono e a chamada ao provedor.
- **Rastro de cada solicitação:** os logs registram criação, cada chamada ao provedor (com o número da tentativa), o resultado e o reagendamento; a tabela guarda `attempts`, `last_error` e `next_attempt_at`, o que já permite consultar no banco quantas solicitações estão em cada status.
- **Logs JSON:** `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs` (padrão no compose).
- **Dados sensíveis:** tokens e a chave do provedor nunca são logados.

---

## Estratégia de testes

`./mvnw verify` roda tudo. A divisão segue o que cada nível consegue provar:

| Nível | Classe | O que garante |
|---|---|---|
| Unitário, sem Spring | `ActivationRequestTest` | Transições válidas e inválidas (`COMPLETED` não volta para `PROCESSING`, etc.), contagem de tentativas. |
| Unitário, sem Spring | `RetryPolicyTest` | Backoff exponencial dentro da faixa do jitter, teto, `Retry-After`, limite de tentativas. |
| Unitário com Mockito | `ActivationProcessorTest` | Mapeamento de cada resultado do provedor para o status; não chama o provedor se outra instância reivindicou; reconciliação. |
| Integração HTTP (WireMock) | `HttpProviderGatewayTest` | Classificação real de 2xx/4xx/5xx/429/503, `Retry-After` (segundos e data), timeout de leitura, conexão recusada, headers de idempotência e correlação. |
| Fatia web (`@WebMvcTest`) | `ActivationRequestControllerTest` | Contrato HTTP: 201 + `Location`, 200 em repetição, 400 com `errors`, 404, 409, 413, 500 sem detalhes internos, 401 sem token, 403 com escopo insuficiente. |
| Integração com banco (H2) | `DuplicateCreationIntegrationTest` | Duplicidade sequencial, conteúdo divergente, outro consumidor e **16 requisições simultâneas → exatamente 1 registro**. |
| Integração ponta a ponta (H2 + WireMock + Awaitility) | `ActivationFlowIntegrationTest` | API → banco → dispatcher → provedor → status final: sucesso, 400, 500 seguido de sucesso (mesma `Idempotency-Key`), timeout até `FAILED`, isolamento entre consumidores, listagem com filtros. |
| Integração com PostgreSQL real (Testcontainers) | `PostgresIntegrationTest` | Migrações Flyway válidas no PostgreSQL e o teste de concorrência contra o banco de produção. Pulado sem Docker. |
| Arquitetura (ArchUnit) | `ArchitectureTest` | Models e services não dependem de controllers, HTTP nem do cliente do provedor; repositórios só são usados pelos services. |

O critério foi testar cada regra no nível mais barato que ainda a prova: regras de domínio sem Spring; contrato HTTP numa fatia web com o serviço mockado; e o que depende de banco, concorrência ou rede real (unicidade, transações, timeouts) com banco e servidor HTTP de verdade.

---

## Cenário de análise

> Após entrar em produção: tempo médio da API 300 ms → 2,5 s; CPU 45%; memória 60%; algumas consultas 50 ms → 1,2 s; serviço externo 400 ms → 3 s.

**1. O que você investigaria primeiro?**

CPU (45%) e memória (60%) estão normais, então a aplicação não está saturada calculando — ela está **esperando** alguma coisa. Primeiro eu olharia *onde* o tempo das requisições lentas é gasto: quais endpoints pioraram (a média esconde a distribuição: poucas requisições muito lentas puxam a média para cima) e quando começou (coincide com um deploy, um aumento de volume ou uma mudança do provedor?). Em seguida, os recursos pelos quais a aplicação espera: o pool de conexões do banco (HikariCP) e as threads do Tomcat.

**2. Como identificaria se o problema está na aplicação, no banco ou na integração externa?**

Decompondo o tempo de uma requisição lenta, seguindo o `correlationId` nos logs: quanto foi gasto esperando conexão, executando SQL e chamando o provedor. No banco, comparo com o tempo das próprias queries (`pg_stat_statements`); se a query é rápida no banco mas lenta na aplicação, o tempo está na espera por conexão (pool), não no SQL. Se a latência da API ≈ espera por conexão + query, é o banco (ou o pool); se acompanha a chamada ao provedor, é a integração. Nesta aplicação, a criação **não** chama o provedor de forma síncrona, então lentidão do provedor não deveria aparecer diretamente na API — se aparecer, algo está compartilhando recursos.

**3. Quais logs ou métricas seriam úteis?**

- API: log de acesso com tempo de resposta por endpoint e status (para ver a distribuição, não só a média); threads ocupadas do Tomcat.
- Banco: estado do pool do HikariCP (conexões ativas, aguardando e timeouts de aquisição, que aparecem no log como `Connection is not available, request timed out`), `pg_stat_statements` (tempo médio e chamadas por query), `pg_stat_activity` (queries em espera e locks), slow query log, plano de execução (`EXPLAIN ANALYZE`) das consultas que pioraram, tamanho das tabelas e índices.
- Provedor: duração de cada chamada, taxa de erro e de timeout (logados pelo processador com o `correlationId`).
- Fila interna: `select status, count(*) from activation_request group by status`; `PENDING` crescendo indica que o dispatcher não dá conta.
- JVM: pausas de GC, threads bloqueadas (thread dump).
- Logs com `correlationId` para seguir uma requisição lenta específica.

**4. Que tipo de melhoria poderia ser considerada?**

- Banco: índice que falta para a consulta que piorou (ex.: listagem sem índice adequado após a tabela crescer), paginação com limite máximo, revisão de N+1, `VACUUM/ANALYZE`, separar leituras pesadas em réplica.
- Pool e transações: garantir que nenhuma conexão fica presa durante I/O externo (já é o caso aqui: a chamada ao provedor é fora de transação); dimensionar o pool.
- Provedor: timeouts adequados, chamada assíncrona (já é), circuit breaker para cortar rápido quando ele degrada, bulkhead para que a lentidão dele não consuma os recursos da API.
- Escala: mais instâncias só ajudam se o gargalo não for o banco.

**5. Os três sintomas podem ter uma causa comum?**

Sim. Hipótese: **o provedor ficou lento e a chamada a ele está segurando conexões do banco.** Se a chamada HTTP acontecesse dentro de uma transação (ou com `open-in-view` ligado), cada chamada de 3 s manteria uma conexão do pool ocupada. O pool esgota; as demais requisições ficam esperando conexão — e essa espera aparece como "consulta lenta" (50 ms → 1,2 s) e como latência da API, enquanto CPU e memória continuam normais, porque tudo está parado esperando. Outra variante: um aumento de volume que piora o provedor e o banco ao mesmo tempo.

Como confirmar: requisições aguardando conexão no HikariCP (ou timeouts de aquisição no log) no mesmo período em que as chamadas ao provedor ficaram lentas; em `pg_stat_activity`, conexões `idle in transaction` durante a chamada; no thread dump, threads do Tomcat bloqueadas em `HikariPool.getConnection` e outras em I/O de socket do cliente HTTP. A prova final: simular o provedor lento (WireMock com delay) em homologação e reproduzir a degradação. Esta aplicação foi desenhada para evitar exatamente isso (chamada fora de transação, `open-in-view=false`, envio assíncrono).

**6. Como validaria que a correção funcionou depois de publicada?**

Comparando os mesmos indicadores antes e depois: tempo de resposta por endpoint, espera por conexão no pool, duração das queries (`pg_stat_statements` zerado antes do deploy) e duração das chamadas ao provedor. Antes de publicar, um teste de carga em homologação com o provedor simulado lento (WireMock com delay) confirma que a API não degrada mais quando o provedor degrada. Depois de publicar, acompanho os mesmos indicadores por alguns dias, incluindo os horários de pico, para garantir que a melhora se mantém.

---

## Produção

A imagem gerada pelo `Dockerfile` é a mesma usada em qualquer ambiente; o que muda são as variáveis de ambiente.

```bash
docker build -t deroyque:<versão> .
docker run -d -p 8080:8080 \
  -e SPRING_DATASOURCE_URL=jdbc:postgresql://<host>:5432/<db> \
  -e SPRING_DATASOURCE_USERNAME=<user> \
  -e SPRING_DATASOURCE_PASSWORD=<senha> \
  -e OAUTH_JWK_SET_URI=https://<auth-server>/.well-known/jwks.json \
  -e OAUTH_ISSUER_URI=https://<auth-server> \
  -e PROVIDER_BASE_URL=https://<provedor> \
  -e PROVIDER_API_KEY=<segredo> \
  -e LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs \
  --memory=512m \
  deroyque:<versão>
```

Em produção, eu executaria a mesma imagem com Docker, como faço hoje no BarberTop:

- **Várias instâncias** atrás de um load balancer (ou proxy reverso, como Nginx), que só envia tráfego para instâncias com `/actuator/health` respondendo `UP`.
- **Banco gerenciado** (RDS/Cloud SQL/Azure Database for PostgreSQL), com backup e réplica de leitura se necessário. Migrações Flyway rodam na subida da aplicação (o Flyway usa lock no banco, então várias instâncias subindo juntas não aplicam a mesma migração duas vezes); em ambientes maiores, eu as rodaria como etapa separada do pipeline.
- **Segredos** (senha do banco, chave do provedor) no serviço de segredos da cloud, injetados como variáveis de ambiente; nunca na imagem.
- **Desligamento gracioso:** `server.shutdown=graceful` termina as requisições em andamento; uma solicitação interrompida no meio da chamada ao provedor é retomada pela reconciliação.
- **Logs:** em JSON (`LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs`), centralizados num agregador para buscar pelo `correlationId`; alerta quando o número de solicitações em `PENDING` ou `FAILED` cresce fora do normal.

### Microsserviços e cloud

- **Várias instâncias sem quebrar as regras:** a duplicidade é garantida pela constraint única no banco, independente do número de instâncias. O processamento usa lock otimista para que só uma instância reivindique cada solicitação; com volume alto, eu trocaria a busca por `SELECT ... FOR UPDATE SKIP LOCKED` (PostgreSQL) para que as instâncias peguem lotes diferentes em vez de disputar os mesmos.
- **Configurações e segredos:** configurações não sensíveis em variáveis de ambiente; segredos no serviço de segredos da cloud (AWS Secrets Manager, Azure Key Vault, GCP Secret Manager), com rotação.
- **Avisar outros microsserviços:** ao concluir (ou falhar) uma ativação, publicar um evento `ActivationCompleted`/`ActivationFailed` num tópico (Kafka, SNS/SQS, RabbitMQ). Para não perder eventos, usaria o mesmo padrão outbox: gravar o evento numa tabela na mesma transação que muda o status, e um publicador (ou Debezium/CDC) envia ao broker.
- **CI/CD:** a cada push, o pipeline (`.github/workflows/ci.yml`) compila e roda todos os testes, incluindo Testcontainers, e constrói a imagem Docker. Na sequência, em um pipeline completo: scan de vulnerabilidades da imagem, publicação no registry com a versão/commit como tag, deploy automático em homologação, testes de fumaça, e promoção para produção substituindo as instâncias uma a uma, sem derrubar o serviço.

---

## Pontos pendentes

- **Limite de taxa de chamadas:** não implementado na aplicação. Ficaria no API Gateway (ou no proxy reverso), por `client_id`, porque um limite em memória vale só por instância. Se precisasse ficar no serviço, usaria Bucket4j com estado no Redis.
- **Limite de corpo para requisições chunked:** o filtro barra pelo `Content-Length`; corpos `chunked` sem tamanho declarado seriam barrados no gateway/proxy reverso.
- **Circuit breaker:** descrito, não implementado (ver abaixo).
- **Tracing distribuído (OpenTelemetry):** não implementado; hoje a correlação é feita pelo `X-Correlation-Id`. Adicionaria `spring-boot-starter-opentelemetry` exportando para um coletor OTLP, propagando `traceparent` até o provedor.
- **Chave de idempotência no provedor:** a aplicação envia a `Idempotency-Key`, mas o efeito depende de o provedor suportá-la. Se não suportasse, antes de repetir após um timeout eu consultaria o estado da ativação no provedor.
- **Testes com PostgreSQL real:** dependem de Docker na máquina; no CI (GitHub Actions) eles rodam.

---

## Melhorias futuras

- **Circuit breaker (Resilience4j)** no cliente do provedor: com o circuito aberto, o dispatcher pula o ciclo em vez de gastar tentativas em um provedor sabidamente fora do ar.
- **`SELECT ... FOR UPDATE SKIP LOCKED`** no dispatcher, para várias instâncias pegarem lotes diferentes sob volume alto.
- **Disparo imediato após o commit** (evento `@TransactionalEventListener`), mantendo o polling como rede de segurança, para reduzir a latência até a ativação.
- **Eventos de domínio** (ativação concluída/falhou) publicados via outbox em um broker.
- **Reprocessamento manual** de solicitações `FAILED` (endpoint administrativo com escopo próprio) e uma fila de "dead letter" para análise.
- **Índice parcial** no PostgreSQL para o dispatcher e **expurgo/arquivamento** de solicitações antigas finalizadas.
- **OpenAPI** (springdoc) para documentar o contrato da API.
- **Testes de carga** (Gatling/k6) com o provedor simulado lento, como parte da validação de performance.
- **Indicadores de negócio** com alertas (tempo até `COMPLETED`, taxa de `FAILED` por produto).

---

## Uso de IA

Ferramenta utilizada: **Claude Code** (Anthropic, modelo Claude Opus 5.5), via terminal.

Todas as partes abaixo foram geradas com IA e revisadas por mim:

| Parte | O que a IA fez |
|---|---|
| `Dockerfile` | Escreveu o Dockerfile multi-stage (build com Maven, runtime com JRE Alpine, usuário não-root, `JAVA_OPTS` com `MaxRAMPercentage`). |
| `docker-compose.yml` | Criou os serviços `db` (PostgreSQL 17 com health check e volume) e `app` (dependente do banco saudável, configurado por variáveis de ambiente). |
| `.env.example` | Criou o modelo de variáveis de ambiente. |
| `.dockerignore` e `.gitignore` | Criou o `.dockerignore` e adicionou `.env` ao `.gitignore`. |
| `pom.xml` | Adicionou as dependências `spring-boot-starter-data-jpa`, `postgresql` e `flyway-database-postgresql`. |
| `application.properties` | Configurou datasource por variáveis de ambiente, `ddl-auto=validate`, `open-in-view=false`, Flyway e desligamento gracioso. |
| `src/main/resources/db/migration/` | Criou a pasta de migrações do Flyway (vazia). |
| `README.md` | Escreveu este README a partir do enunciado do desafio (estrutura, seções obrigatórias, checklist de escopo e instruções de execução). |
| `docs/adr/` | Escreveu os ADRs (duplicidade por constraint única, processamento assíncrono com outbox, retentativas e transições de status, segurança com Resource Server JWT). |
| `src/test/` | Escreveu os testes automatizados: unitários (`ActivationRequestTest`, `RetryPolicyTest`, `ActivationProcessorTest`), HTTP com WireMock (`HttpProviderGatewayTest`), fatia web (`ActivationRequestControllerTest`), integração (`DuplicateCreationIntegrationTest`, `ActivationFlowIntegrationTest`, `PostgresIntegrationTest`) e arquitetura (`ArchitectureTest`). |

_Esta tabela deve ser atualizada a cada nova parte feita com auxílio de IA._

# ADR 0001 — Duplicidade garantida por constraint única no banco

**Status:** aceito

## Contexto

O `externalId` identifica a solicitação no sistema de origem, que faz retry. Duas requisições iguais podem
chegar quase ao mesmo tempo, inclusive em instâncias diferentes da aplicação.

"Consultar se existe e depois inserir" não basta: as duas requisições podem fazer a consulta antes de
qualquer uma inserir, ambas veem "não existe" e ambas inserem (condição de corrida *check-then-act*).
Um lock em memória (`synchronized`) não resolve com mais de uma instância.

## Decisão

- `UNIQUE (external_id)` na tabela é a garantia final. O banco serializa os dois `INSERT`s: um é aceito,
  o outro falha com violação de unicidade.
- A aplicação insere numa transação curta; se receber `DataIntegrityViolationException`, lê o registro
  vencedor (fora da transação que falhou) e responde como repetição.
- Uma consulta prévia por `externalId` atende o caso comum (retry sequencial) sem gerar erro no banco.
  Ela é só otimização: a correção não depende dela.

## Semântica da resposta

| Cenário | Resposta |
|---|---|
| Mesmo `externalId`, mesmo conteúdo e mesmo consumidor | `200 OK` com o registro existente e `Location` |
| Mesmo `externalId`, conteúdo diferente (ou de outro consumidor) | `409 Conflict` (Problem Details) |
| Duas requisições idênticas em paralelo | Uma recebe `201`, a outra `200` com o mesmo `id` |

`200` para o retry legítimo torna o POST idempotente: o sistema de origem não precisa tratar `409` como
"talvez tenha dado certo". `409` fica reservado para o caso que é de fato um erro do chamador.

## Consequências

- Funciona com qualquer número de instâncias, sem lock distribuído.
- O banco registra um erro de constraint no log quando há corrida; é esperado e raro.

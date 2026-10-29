# ADR 0003 — Retentativas, idempotência e transições de status

**Status:** aceito

## Contexto

Cada retorno do provedor precisa virar um status, e falhas temporárias precisam de nova tentativa sem
ativar o produto duas vezes.

## Decisão

**Transições** (validadas na entidade, sem setters):

```
PENDING → PROCESSING → COMPLETED
              │      → FAILED
              └──────→ PENDING   (falha temporária, nova tentativa agendada)
```

`COMPLETED` e `FAILED` são finais. Uma falha temporária **volta para `PENDING`** com `attempts`
incrementado e `next_attempt_at` no futuro, em vez de ficar em `PROCESSING`: assim `PROCESSING` significa
sempre "há uma chamada em andamento", e uma solicitação parada em `PROCESSING` por muito tempo é sinal
claro de que a instância caiu (ver ADR 0002).

**Classificação dos retornos:**

| Retorno | Tipo | Motivo |
|---|---|---|
| 2xx | sucesso | — |
| 400 e demais 4xx | definitivo | a requisição é inválida para o provedor; repetir dá o mesmo resultado |
| 408, 429, 5xx | temporário | sobrecarga ou falha do lado do provedor |
| 429/503 com `Retry-After` | temporário | espera pelo menos o tempo pedido |
| timeout / conexão | temporário | ambíguo: o provedor pode ter ativado |

**Backoff:** exponencial a partir de 2 s, teto de 5 min, com *equal jitter* (metade fixa, metade
aleatória) para não sincronizar as retentativas depois de uma queda do provedor. `Retry-After` é
respeitado mesmo acima do teto. Após 5 tentativas a solicitação vai para `FAILED` com o último erro.

**Idempotência:** o `id` da solicitação é enviado como `Idempotency-Key` em todas as tentativas. Depois de
um timeout, a nova tentativa usa a mesma chave e o provedor pode responder "já ativado" sem ativar de novo.
Isso pressupõe que o provedor suporte a chave; se não suportar, a alternativa é consultar o estado no
provedor antes de repetir.

## Consequências

- Nenhuma chamada fica dependente de uma thread esperando: o reagendamento fica no banco e sobrevive a
  reinícios.
- Não há circuit breaker: o backoff com jitter e o limite de tentativas já reduzem a pressão sobre um
  provedor fora do ar. Com volume alto, um circuit breaker (Resilience4j) pularia o ciclo inteiro
  enquanto o provedor estiver aberto.

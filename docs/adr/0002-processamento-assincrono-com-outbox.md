# ADR 0002 — Envio assíncrono ao provedor, com a própria tabela como outbox

**Status:** aceito

## Contexto

O provedor responde em ~400 ms normalmente, mas pode ficar lento (3 s ou mais) ou fora do ar. Se o
`POST /api/v1/requests` esperasse o provedor, a latência e a disponibilidade da API seriam as do
provedor, e cada requisição seguraria uma thread (e talvez uma conexão de banco) durante a chamada.

Também não se pode chamar o provedor dentro da transação que grava a solicitação: a conexão ficaria
presa durante a chamada HTTP, e se o commit falhasse depois da chamada o produto estaria ativado sem
registro.

## Decisão

- O POST só grava a solicitação como `PENDING` (com `next_attempt_at = agora`) e retorna `201`.
- Um dispatcher agendado (`@Scheduled`, a cada 1 s) busca as solicitações `PENDING` vencidas e as
  processa em paralelo (threads virtuais), em três passos:
  1. transação curta: `PENDING → PROCESSING` (reivindicação, protegida por `@Version`);
  2. chamada HTTP ao provedor, **sem transação aberta**;
  3. transação curta: grava o resultado.
- A tabela funciona como outbox: se a aplicação cair entre o commit e a chamada, a solicitação continua
  `PENDING` e é enviada no próximo ciclo, por esta ou outra instância.
- Se cair durante a chamada, a solicitação fica `PROCESSING`; um segundo agendamento devolve para
  `PENDING` as que estão em `PROCESSING` há mais de `activation.stuck-after` (2 min, maior que o timeout
  de leitura).

## Consequências

- A API responde rápido e independe do provedor. O consumidor acompanha o status via `GET`.
- Latência de até ~1 s (intervalo do polling) até a chamada. Aceitável aqui; se não fosse, dá para
  disparar o processamento logo após o commit e manter o polling como rede de segurança.
- Com várias instâncias, duas podem tentar reivindicar a mesma solicitação: o lock otimista faz uma delas
  desistir antes de chamar o provedor.

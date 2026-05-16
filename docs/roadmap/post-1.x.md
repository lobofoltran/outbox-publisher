# Pós-1.x — evoluções permitidas + non-goals

Após 1.0, qualquer prompt aqui só sai do papel se:

- Existir adopter externo identificado pedindo.
- A mudança couber dentro das políticas das ADRs 0021-0023.
- Não conflitar com `NON-GOALS.md`.

Caso contrário, rejeitar o PR sem discussão técnica.

---

## Permitido — sob demanda

### Prompt A — Dialeto Oracle / MSSQL / DB2

**Quando.** Adopter externo abre issue pedindo, com volume estimado.

**Commit prefix:** `feat(outbox-jdbc):`

**Tarefa esqueleto.**

1. Implementar `OracleDialect` (ou MSSQL/DB2) seguindo o template do
   `MySqlDialect` (entregue em v0.6).
2. Idempotência: `MERGE INTO ... WHEN NOT MATCHED THEN INSERT` em
   Oracle/MSSQL; `INSERT OR IGNORE` não existe — usar MERGE.
3. UUID binding: Oracle usa `RAW(16)`; MSSQL tem `UNIQUEIDENTIFIER`.
4. JSON: Oracle 21c+ tem `JSON` nativo; antes, `CLOB CHECK (IS JSON)`.
   MSSQL 2016+ tem JSON via `NVARCHAR(MAX)` + funções `JSON_*`.
5. Adicionar DDL ao `outbox-schema` em pasta nova.
6. TCK estende para o novo dialeto.

**Restrições.**

- **Não** mexer no SPI core sem ADR.
- Se o SPI atual não couber, **pare** e abra issue antes de codificar
  workaround.

### Prompt B — `outbox-publisher-reactive` (R2DBC)

**Quando.** Adopter externo com app reactive identificado e
disposto a beta-testar.

**Importante.** Esta **não** é uma evolução do `outbox-jdbc`. É **outra
SDK** num **outro repo** (ou no mesmo, mas em módulo absolutamente
isolado), porque:

- `Connection` (JDBC) vs `io.r2dbc.spi.Connection` são tipos incompatíveis.
- Transação reactive tem semântica diferente (subscriber chain, não
  ThreadLocal).
- Misturar no mesmo módulo polui o caminho clássico.

**Tarefa esqueleto.**

1. Novo repo `outbox-publisher-reactive` ou módulo `outbox-r2dbc` com
   `module-info` próprio.
2. `Outbox` reactive não pode reusar a mesma interface (assinatura
   `void publish(...)` é incompatível). Definir `ReactiveOutbox` em
   namespace separado.
3. Compartilhar `OutboxEvent` (move para um `outbox-shared`?) ou
   duplicar (custo baixo).
4. Aplicar mesma disciplina: 1 método, sem polling, sem broker.

**Decisão.** Provavelmente repo separado é melhor que módulo. Vai por
ADR antes de código.

### Prompt C — Quarkus extension `outbox-quarkus`

**Quando.** Adopter externo identificado.

**Tarefa.**

1. Novo módulo `outbox-quarkus` seguindo o template Quarkus extension
   (deployment + runtime artifacts).
2. Provê CDI bean `Outbox` ligado ao `AgroalDataSource` da app.
3. `@ConfigMapping` substitui `OutboxProperties`.
4. Decorators de obs (Micrometer, OTel) seguem o padrão Quarkus
   (Micrometer Quarkus, Quarkus OpenTelemetry).
5. Health check via `MicroProfile Health`, opt-in.

**Restrição.** Mesma disciplina de minimalismo do `outbox-spring`.

### Prompt D — Maven Central migration

**Quando.** Demanda externa real (lib citada em projeto que não pode
consumir GitHub Packages).

**Tarefa.** Executar ADR-0003.

---

## Permitido — manutenção contínua

- **Bump de dependências runtime** (Spring Boot, OTel, Micrometer):
  PRs do Dependabot, aceitar se CI verde.
- **Tracking de OpenTelemetry semantic conventions** quando saírem de
  experimental para stable.
- **Bug fixes** em `outbox-core`/`outbox-jdbc`: sempre `patch` bump,
  nunca breaking.

---

## Bloqueado — `NON-GOALS.md`

Os seguintes pedidos devem ser **rejeitados sem revisão técnica**, com
link para `NON-GOALS.md`:

1. `Outbox.publishAsync(...)` retornando `CompletableFuture`/`Mono` no
   módulo jdbc (vs. reactive em repo/módulo separado — esse sim).
2. Polling/scheduler embutido ("só pra dev rodar local").
3. Consumer API: `OutboxListener`, `@OutboxHandler`, etc.
4. Broker connector (Kafka producer, SQS sender, RabbitMQ helper) — de
   qualquer tipo, em qualquer módulo.
5. Retry com backoff state mantido pelo Outbox.
6. JPA `EntityManager` bridge.
7. Flyway/Liquibase bundled.
8. JSON/CBOR/Avro serializer para `payload`.
9. `OutboxTemplate` ou helper "transactional".
10. Auto-discovery de `Outbox` via `ServiceLoader` (mesmo "opcional").
11. Saga / process manager helpers.
12. Event sourcing utilities.
13. CDC sink / Debezium bridge embutido.
14. `tenant_id`/`correlation_id` como **colunas dedicadas** — use headers.
15. SDK passar a entender semântica do payload de qualquer forma.

**Forma de rejeição.** Comentário curto no PR/issue:

> Isso está em `NON-GOALS.md`. Fechando.

Sem alongar discussão técnica. A discussão de princípio já foi feita;
abrir de novo desgasta o projeto.

---

## Quando reavaliar non-goals

Apenas em **major bump** (2.0+), e somente se:

- Foram pedidos por ≥3 adopters externos independentes em issues
  separadas.
- A demanda é articulada com caso de uso, não preferência estética.
- Pelo menos uma alternativa via decorator/extension foi tentada e
  documentada como insuficiente.

Mesmo nesses casos, prefira **outro repo** a inflar este. A lição
ShedLock é clara: a versão minimalista sobreviveu 10+ anos. A versão
que tentou virar plataforma morre.

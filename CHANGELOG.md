# Changelog

All notable changes to this project will be documented in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to [Conventional Commits](https://www.conventionalcommits.org/) and [Semantic Versioning](https://semver.org/). Releases are managed automatically by [release-please](https://github.com/googleapis/release-please).

## [0.4.3](https://github.com/lobofoltran/outbox-publisher/compare/v0.4.2...v0.4.3) (2026-05-16)


### Bug Fixes

* **javadoc:** publish from target/reports/apidocs ([#79](https://github.com/lobofoltran/outbox-publisher/issues/79)) ([af554f0](https://github.com/lobofoltran/outbox-publisher/commit/af554f00f67e570ce7c09cf5b98c2a87870562cf))

## [0.4.2](https://github.com/lobofoltran/outbox-publisher/compare/v0.4.1...v0.4.2) (2026-05-16)


### Bug Fixes

* **release:** tolerate transient javadoc.io fetch failures during attach-javadocs ([#77](https://github.com/lobofoltran/outbox-publisher/issues/77)) ([6de0da6](https://github.com/lobofoltran/outbox-publisher/commit/6de0da6ba56f9fda54e882a481c3f6df81bbc2b0))

## [0.4.1](https://github.com/lobofoltran/outbox-publisher/compare/v0.4.0...v0.4.1) (2026-05-16)


### Bug Fixes

* **outbox-spring,javadoc:** unblock javadoc:aggregate by naming the automatic module ([#75](https://github.com/lobofoltran/outbox-publisher/issues/75)) ([61e64ac](https://github.com/lobofoltran/outbox-publisher/commit/61e64ac8d15424bd79823a8f14f4a1fe9bdac78c))

## [0.4.0](https://github.com/lobofoltran/outbox-publisher/compare/v0.3.0...v0.4.0) (2026-05-16)


### ⚠ BREAKING CHANGES

* **outbox-core:** OutboxEvent.Builder and the OutboxEvent compact constructor no longer throw NullPointerException / IllegalArgumentException; they throw OutboxValidationException (extends OutboxException). Callers that explicitly catch NPE/IAE from event construction must update their catch blocks. PostgresDialect.validate() likewise throws OutboxDataException instead of IllegalArgumentException.

### Features

* **outbox-core:** close sealed hierarchy with OutboxValidationException (DEBT-08) ([#70](https://github.com/lobofoltran/outbox-publisher/issues/70)) ([b2f75d8](https://github.com/lobofoltran/outbox-publisher/commit/b2f75d8509b7b9efecf02bd08772d10aea49568d))
* **outbox-otel:** strip URI scheme from messaging.destination.name ([#67](https://github.com/lobofoltran/outbox-publisher/issues/67)) ([cad4c08](https://github.com/lobofoltran/outbox-publisher/commit/cad4c08cdde463c463e4124f15d96f5bc7cd2bcc))


### Bug Fixes

* **outbox-spring:** order autoconfig after Micrometer & OTel autoconfigs ([#66](https://github.com/lobofoltran/outbox-publisher/issues/66)) ([fb61f05](https://github.com/lobofoltran/outbox-publisher/commit/fb61f05c8e98eff748bf5b1fc37d45ca26c465e7))
* **outbox-spring:** resolve OpenTelemetry from global when no bean is registered ([#65](https://github.com/lobofoltran/outbox-publisher/issues/65)) ([e0ccdf7](https://github.com/lobofoltran/outbox-publisher/commit/e0ccdf7af9cd0fd92b9eec7fe83581f91d98e111))
* **outbox-spring:** warn instead of silently skipping decorators when collaborator beans are missing ([#69](https://github.com/lobofoltran/outbox-publisher/issues/69)) ([e74c9c0](https://github.com/lobofoltran/outbox-publisher/commit/e74c9c0e2bc21279c73f4329c24c3e39958ab7a3))

## [0.3.0](https://github.com/lobofoltran/outbox-publisher/compare/v0.2.0...v0.3.0) (2026-05-16)


### ⚠ BREAKING CHANGES

* **outbox-jdbc:** OutboxDialect's bindId / bindHeaders / bindTimestamp / bindOptionalString / insertSql methods are removed in favor of prepareInsert. Third-party dialects must migrate. Targeted for 0.2.0.
* **outbox-core:** OutboxEvent no longer rejects long strings in its constructor. Callers that relied on the IllegalArgumentException being thrown at OutboxEvent construction time now see it raised at outbox.publish(event) time instead, and the message now reports byte length ("128 bytes (UTF-8)") rather than character count ("128 characters"). Behaviour against PostgreSQL is unchanged for ASCII-only fields; multibyte fields are now correctly rejected on the true byte boundary. Custom OutboxDialect implementations remain source-compatible thanks to the default no-op validate() method.

### Features

* **outbox-core:** eager null validation in OutboxEvent.Builder ([#49](https://github.com/lobofoltran/outbox-publisher/issues/49)) ([8f89700](https://github.com/lobofoltran/outbox-publisher/commit/8f89700dba0bcb64bb9e56fcc586c7cd73756127))
* **outbox-jdbc:** cascade ServiceLoader through TCCL and system loader ([#55](https://github.com/lobofoltran/outbox-publisher/issues/55)) ([c97b453](https://github.com/lobofoltran/outbox-publisher/commit/c97b45367e611b69773b0f81857eb6f854e4bbae))
* **outbox-micrometer:** tag-value cardinality cap ([#62](https://github.com/lobofoltran/outbox-publisher/issues/62)) ([d84ea82](https://github.com/lobofoltran/outbox-publisher/commit/d84ea82669da1443c8569aebdd95815720956963))
* **outbox-otel:** inject W3C trace context into event headers ([#57](https://github.com/lobofoltran/outbox-publisher/issues/57)) ([351e7c9](https://github.com/lobofoltran/outbox-publisher/commit/351e7c927250232044ca8c2eb5a578fced026ce4))


### Bug Fixes

* **build:** align pitest-junit5-plugin with JUnit Platform 6 ([#59](https://github.com/lobofoltran/outbox-publisher/issues/59)) ([9d81b59](https://github.com/lobofoltran/outbox-publisher/commit/9d81b59a63e78e89dbb2a23a267d785a29c7c637))
* **ci:** drop release-please package-name so tagger matches merged release PRs ([#60](https://github.com/lobofoltran/outbox-publisher/issues/60)) ([075e182](https://github.com/lobofoltran/outbox-publisher/commit/075e182bf446ce91a2ca72209c2790962bcf6b84))
* **outbox-jdbc:** refine PostgreSQL SQLState classification ([#48](https://github.com/lobofoltran/outbox-publisher/issues/48)) ([52aaba4](https://github.com/lobofoltran/outbox-publisher/commit/52aaba429983c58cec12c464920b4870523b0cd6))
* **outbox-otel:** tolerate null event id in span attributes ([#50](https://github.com/lobofoltran/outbox-publisher/issues/50)) ([69590fb](https://github.com/lobofoltran/outbox-publisher/commit/69590fbddd6e0595aa03e6d60802d7dbabf1e575))


### Code Refactoring

* **outbox-core:** move column-width validation to the dialect ([#58](https://github.com/lobofoltran/outbox-publisher/issues/58)) ([ca60c09](https://github.com/lobofoltran/outbox-publisher/commit/ca60c09fe0dad508c70387302ca856bfcca5a252))
* **outbox-jdbc:** replace index-based dialect SPI with prepare/bind handle ([#54](https://github.com/lobofoltran/outbox-publisher/issues/54)) ([c520f15](https://github.com/lobofoltran/outbox-publisher/commit/c520f159501288f4d9ad613b27e21ea4f605acda))

## [0.2.0](https://github.com/lobofoltran/outbox-publisher/compare/v0.1.0...v0.2.0) (2026-05-16)


### Features

* **outbox-core:** add OutboxEvent.payloadSize() to avoid clone on metric path ([#32](https://github.com/lobofoltran/outbox-publisher/issues/32)) ([4f0b7a6](https://github.com/lobofoltran/outbox-publisher/commit/4f0b7a643199b6bfe8c94f915b31b9072bc8b23e))
* **outbox-core:** seal OutboxException with four typed subclasses ([#30](https://github.com/lobofoltran/outbox-publisher/issues/30)) ([565e5e5](https://github.com/lobofoltran/outbox-publisher/commit/565e5e54ab87c5dcae5578a9144767aea8d88cb7))
* **outbox-jdbc:** introduce OutboxDialect SPI; PostgreSQL as first dialect ([#34](https://github.com/lobofoltran/outbox-publisher/issues/34)) ([1ac803d](https://github.com/lobofoltran/outbox-publisher/commit/1ac803d5073060a0cba525834a81a07cdc5dfd24))
* **outbox-otel:** tracing decorator with OpenTelemetry semantic conventions ([#36](https://github.com/lobofoltran/outbox-publisher/issues/36)) ([92fca14](https://github.com/lobofoltran/outbox-publisher/commit/92fca14ac0a0068563bfb101ea691b12313d3c3b))
* **outbox-spring:** builder customizer SPI, nested properties, config metadata, deterministic decoration order, health indicator ([#44](https://github.com/lobofoltran/outbox-publisher/issues/44)) ([ceb2bbe](https://github.com/lobofoltran/outbox-publisher/commit/ceb2bbed3425fbf41c01582dcc8e5eafe114c605))
* **outbox-tck,outbox-otel:** complete contract test surface and instrumentation metadata ([#43](https://github.com/lobofoltran/outbox-publisher/issues/43)) ([38df304](https://github.com/lobofoltran/outbox-publisher/commit/38df3041043fc28e588a04e9895c91fc86780d48))
* **outbox-tck:** introduce contract test kit for OutboxDialect implementations ([#37](https://github.com/lobofoltran/outbox-publisher/issues/37)) ([0e43729](https://github.com/lobofoltran/outbox-publisher/commit/0e437299b7f252fcfb75ea87866ebfddc036c89e))


### Bug Fixes

* **ci:** drop release-as=0.1.0 so release-please computes next version ([#38](https://github.com/lobofoltran/outbox-publisher/issues/38)) ([864b558](https://github.com/lobofoltran/outbox-publisher/commit/864b55822fefb4ced1c4a0eaee5f47f58e68cfcc))

## 0.1.0 (2026-05-16)


### Features

* **outbox-bom:** add smoke test that imports the BOM from a real consumer ([#24](https://github.com/lobofoltran/outbox-publisher/issues/24)) ([eab44bf](https://github.com/lobofoltran/outbox-publisher/commit/eab44bfa9bdb2fa3f7a3ea5debbd179e635761eb))
* **outbox-core:** F3 — Outbox API with OutboxEvent record and exceptions ([#19](https://github.com/lobofoltran/outbox-publisher/issues/19)) ([d918376](https://github.com/lobofoltran/outbox-publisher/commit/d91837672c758bd5610fe1e2f00b4bc1769eb17f))
* **outbox-jdbc:** F5 — JDBC implementation with real-PG integration tests ([#21](https://github.com/lobofoltran/outbox-publisher/issues/21)) ([4702eb5](https://github.com/lobofoltran/outbox-publisher/commit/4702eb5eb21bd4e23f7b5fc55830d1a8158b99ba))
* **outbox-micrometer:** F6.5 — Micrometer instrumentation decorator ([#23](https://github.com/lobofoltran/outbox-publisher/issues/23)) ([cb740db](https://github.com/lobofoltran/outbox-publisher/commit/cb740db3ec92308c06a473ebb0a542e0a85b5986))
* **outbox-schema:** F4 — ship PostgreSQL DDL as classpath resource ([#20](https://github.com/lobofoltran/outbox-publisher/issues/20)) ([715faa4](https://github.com/lobofoltran/outbox-publisher/commit/715faa489b6ccb3ce38168c8344f560f64842a5d))
* **outbox-spring:** F6 — Spring Boot 4 autoconfiguration ([#22](https://github.com/lobofoltran/outbox-publisher/issues/22)) ([9f86f3a](https://github.com/lobofoltran/outbox-publisher/commit/9f86f3a63f7c4a0d44af16f7a4a183c75fd4405b))


### Bug Fixes

* **ci:** force first release to 0.1.0 instead of release-please default 1.0.0 ([#28](https://github.com/lobofoltran/outbox-publisher/issues/28)) ([223e587](https://github.com/lobofoltran/outbox-publisher/commit/223e587d2252348bf4ba2f1bdd9406edf53ca0b6))
* **ci:** use local-name() XPath so release-please can locate &lt;revision&gt; ([#25](https://github.com/lobofoltran/outbox-publisher/issues/25)) ([4b5217e](https://github.com/lobofoltran/outbox-publisher/commit/4b5217eb8afd06021b2298c6b53e6ae2ea91a212))

## [Unreleased]

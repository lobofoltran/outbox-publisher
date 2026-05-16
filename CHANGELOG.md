# Changelog

All notable changes to this project will be documented in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to [Conventional Commits](https://www.conventionalcommits.org/) and [Semantic Versioning](https://semver.org/). Releases are managed automatically by [release-please](https://github.com/googleapis/release-please).

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

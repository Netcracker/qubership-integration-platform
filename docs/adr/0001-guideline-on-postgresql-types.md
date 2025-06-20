# Guideline On PostgreSQL types

## Status
**Proposed**

#### Date
2025-06-20

#### Owner

[Ivan Sviridov](https://github.com/Sid775)


#### Participants and approvers

* [Ivan Sviridov](https://github.com/Sid775)
* [Pavels Kletnojs](https://github.com/kletnoe)
* [Sergei Skuratovich](https://github.com/SSNikolaevich)

#### Related ADRs

None

## Context

As PostgreSQL database is widely used by Qubership Integration Platform services, the solid approach to database design is required.
This document provides some base rules that are emerged in the process of Qubership Integration Platform development and support. 

## Decision

* Do not alter already existing tables that are not follow the proposed approach if changes don't affect them.
  Otherwise, one must ensure that your changes don't break anything or change an existing behavior by providing all the required testing: unit, e2e, etc. 
* Use ```timestamptz``` as a type for columns that store timestamps (created at, updated at, etc.).
* Use ```text``` as a type for string columns (id, name, description, etc.).
* Use ```bytea``` as a type for columns that store binary data.

### Justification

* There are a lot of already existing entities in Qubership Integration Platform that are not follow the proposed changes.
  While a new changes to domain models do not affect the way the existing entities are used, it is safer to not alter them.
  Otherwise, you must ensure that your changes don't break anything or change an existing behavior (provide unit and e2e tests, etc.).
* It is [recommended](https://wiki.postgresql.org/wiki/Don't_Do_This#Date.2FTime_storage) to not use types other than ```timestamptz``` to store time information.
  The PostgreSQL Wiki page contains reasons behind this recommendation and describes best practices on usage of date/time related functions.
  
  Usage of ```timestamptz``` is error-proof and simplifies managing of timestamps. For JPA entities' fields, such columns can be defined like:

  ```java
      @Column(columnDefinition = "timestamptz")
      private ZonedDateTime expiresAt;
  ```

* It is [recommended](https://wiki.postgresql.org/wiki/Don't_Do_This#Text_storage) to use a ```text``` type for string columns. 
  PostgreSQL [documentation](https://www.postgresql.org/docs/current/datatype-character.html) on character types states that:
  > There is no performance difference among these three types,
  > apart from increased storage space when using the blank-padded type,
  > and a few extra CPU cycles to check the length when storing into a length-constrained column.
  > While character(n) has performance advantages in some other database systems,
  > there is no such advantage in PostgreSQL;
  > in fact character(n) is usually the slowest of the three because of its additional storage costs.
  > In most situations text or character varying should be used instead.
  
  So there is no reason to use types other than ```text``` for string columns.

* We recommend using ```bytea``` over PG Large Object unless streaming, search in data, or >1GB values is needed.
  Large objects have the following downsides:
  * There is only one large object table per database.
  * Large objects aren't automatically removed when the "owning" record is deleted.
    [See](https://www.postgresql.org/docs/current/lo.html) the ```lo_manage``` function in the ```lo``` module.
  * Since there is only one table, large object permissions have to be handled record by record.
  * Streaming is complicated and has less support by client drivers than simple bytea.
  * It's part of the system schema, so you have limited to no control over options like partitioning and tablespaces.

## Consequences

Having a solid approach to database design leads to a more stable, error-proof, and maintainable solution.

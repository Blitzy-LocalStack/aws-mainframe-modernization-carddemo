# Account Service

> **Purpose.** Document account, customer, card cross-reference, update, and
> inquiry ownership.
>
> **Source of truth.** `COACTVWC`, `COACTUPC`, `CBACT01C`, `CBACT03C`,
> `CBCUS01C`, `COACCT01`, and `services/account-service/**`.

The account schema owns accounts, customers, and card cross-reference access.
Updates preserve the baseline's before-image concurrency intent through the
target version contract.

## Build and Test

```bash
# WHAT: compile and test the account module with common-lib.
# WHY : Assumptions: account DTOs and services depend on shared exact-money,
#       validation, and error contracts.
mvn -B -f services/pom.xml -pl account-service -am test
```

The current module contains its documented package contract but no standalone
application entry point or runtime resource configuration. No local server
command is claimed.

# Provisioning fixture — happy path (master read/print round-trip)

These fixtures drive the master-data read/print programs — `app/cbl/CBACT01C.cbl`
(account), `app/cbl/CBACT02C.cbl` (card), `app/cbl/CBACT03C.cbl` (cross-reference),
and `app/cbl/CBCUS01C.cbl` (customer) — over a small, **fully cross-linked** set of
**five records per master**. It verifies the read/print round-trip: every record is
read and printed, record counts match, and key field extracts are correct.

> **Read [`tests/fixtures/README.md`](../../README.md) first** for the byte-level
> encoding contract and the availability status of the `tests/golden/**` /
> `tests/integration/**` trees. This README does not restate that contract.

## 1. Intent

Each provisioning program sequentially reads its master file and prints/verifies the
records. This scenario provides **five** coherent records in each master so the
tests can assert:

- **Record counts** — each program reads exactly **5** records and reaches EOF
  cleanly (`RETURN-CODE = 0`).
- **Field extracts** — key fields (ids, balances, names, card numbers) decode to the
  expected values.
- **Cross-linkage** — every `cardxref` row resolves to a card, customer, and account
  that exist in the other three masters (see §3).

> **WHY five records rather than one (Assumption / coverage Trade-off).** A single
> record would verify "the program runs" but not that it iterates, counts, and
> reaches EOF over multiple records. Five keeps the fixture small and human-diffable
> while exercising the read loop and giving the count assertion something to check.

## 2. Fixture files in this folder

| File | Copybook | `RECLN` | Line ending | Records | Program | Keys |
|---|---|---:|---|---:|---|---|
| `acctdata.txt` | `CVACT01Y` (ACCOUNT) | 300 | LF | 5 | `CBACT01C` | acct `…002, …012, …020, …027, …050` |
| `carddata.txt` | `CVACT02Y` (CARD) | 150 | LF | 5 | `CBACT02C` | card `0500024453765740`, `0683586198171516`, `0923877193247330`, `0927987108636232`, `0982496213629795` |
| `cardxref.txt` | `CVACT03Y` (XREF) | 50 | LF | 5 | `CBACT03C` | one row per card, mapping card → cust → acct |
| `custdata.txt` | `CVCUS01Y` (CUSTOMER) | 500 | LF | 5 | `CBCUS01C` | cust `000000002, 000000012, 000000020, 000000027, 000000050` |

## 3. Cross-linkage (the five rows resolve consistently)

Each `cardxref` row links a card to a customer and account that **exist** in the
sibling masters:

| card | → cust | → acct |
|---|---|---|
| `0500024453765740` | `000000050` | `00000000050` |
| `0683586198171516` | `000000027` | `00000000027` |
| `0923877193247330` | `000000002` | `00000000002` |
| `0927987108636232` | `000000020` | `00000000020` |
| `0982496213629795` | `000000012` | `00000000012` |

> **WHY the chains are kept consistent (self-containment).** Provisioning verifies
> master integrity, so a dangling cross-reference (a card pointing at a missing
> account/customer) would be a *different* (negative) scenario. The happy path keeps
> every chain resolvable so any failure is attributable to a real read/print defect,
> not to intentionally broken linkage.

## 4. Expected outcome (authoritative intent; golden is [planned])

For the **[planned]** mirror `tests/golden/provisioning/happy_path/` (master §1),
captured from actual output:

- Each of `CBACT01C`, `CBACT02C`, `CBACT03C`, `CBCUS01C` reads and prints **5**
  records and completes with **`RETURN-CODE = 0`**.
- The printed output / extracted fields match the five records above (ids,
  balances, names, card numbers) byte-deterministically.

## 5. Data governance / synthetic provenance (MA-24)

All account ids, card numbers (PANs), customer identities (names, SSNs, DOBs,
addresses, government ids), and cross-reference values are **synthetic,
seed-derived** test data representing **no real person or account** — copied from
the published AWS CardDemo sample datasets `app/data/ASCII/{acctdata,carddata,
cardxref,custdata}.txt`, which ship with the upstream open-source project as
fabricated demonstration data. See master §10 for the full attestation.

## 6. Sources & scope

- **Derived from (never edited):** `app/data/ASCII/{acctdata,carddata,cardxref,custdata}.txt`.
- **Record layouts:** `app/cpy/{CVACT01Y,CVACT02Y,CVACT03Y,CVCUS01Y}.cpy`.
- **Programs:** `app/cbl/{CBACT01C,CBACT02C,CBACT03C,CBCUS01C}.cbl`.
- **Consumed by (when present):** `tests/integration/test_provisioning.py` —
  **[planned]** (master §1).

**Minimal-change principle (mandatory).** Seeds under `app/data/ASCII/` and all
production sources under `app/` are **REFERENCE ONLY and are never modified**
(AAP §0.8.2). Fixtures are derived copies reshaped for this scenario.

---

*This README is the mandatory Explainability artifact (AAP §0.10.1) for the four
static `.txt` fixtures in this folder.*

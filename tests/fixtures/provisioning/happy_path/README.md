# CardDemo — provisioning / happy_path fixtures

A deterministic, 5-record, cross-consistent subset of the four master files, used to
exercise the read/print provisioning programs and asserted by record-count + field-extract
round-trips.

> Read the tree-wide byte-level contract in [`tests/fixtures/README.md`](../../README.md)
> first (fixed-width layout, zoned-decimal sign overpunch, LF/trailing-newline rule). This
> README does not restate it; it records only the facts specific to this scenario.

## 1. Purpose

Each provisioning program opens its indexed master `ORGANIZATION IS INDEXED,
ACCESS MODE IS SEQUENTIAL`, reads every record to EOF, prints it, and returns
`RETURN-CODE = 0`. This fixture provides exactly **five** coherent records per master so a
test can assert three things deterministically: the **record count** (5 per file), the
**field extracts** (ids, balances, names, card numbers decode to known values), and the
**cross-linkage** (every `cardxref` row resolves to a card, customer, and account that
exist in the sibling masters — see §3).

## 2. Files

Four fixed-width fixtures, one per master record type. All are verbatim subsets of the
ASCII seeds in `app/data/ASCII/` (seeds are **never edited** — AAP §0.8.2), except
`cardxref.txt`, which is right-padded 36 → 50 bytes (§7). All files use LF (`\n`) with a
trailing newline, so `wc -l` equals the record count (5).

| File | Copybook / RECLN | # records | Program (ASSIGN name) | Derivation |
|---|---|---:|---|---|
| `acctdata.txt` | `CVACT01Y` (ACCOUNT) / 300 | 5 | `CBACT01C` (`ACCTFILE`) | `grep` acct-ids {2,12,20,27,50} from seed |
| `carddata.txt` | `CVACT02Y` (CARD) / 150 | 5 | `CBACT02C` (`CARDFILE`) | `head -5` of seed |
| `cardxref.txt` | `CVACT03Y` (CARD-XREF) / 50 | 5 | `CBACT03C` (`XREFFILE`) | `head -5` of seed, right-padded 36 → 50 |
| `custdata.txt` | `CVCUS01Y` (CUSTOMER) / 500 | 5 | `CBCUS01C` (`CUSTFILE`) | `grep` cust-ids {2,12,20,27,50} from seed |

Record keys (all the leading field, all pre-sorted ascending): `acctdata` → `ACCT-ID` (11),
`carddata` → `CARD-NUM` (16), `cardxref` → `XREF-CARD-NUM` (16), `custdata` → `CUST-ID` (9).

## 3. Included records — the {2,12,20,27,50} subset + verified linkage

The `cardxref` record links each card to a customer and an account: card `[1-16]` →
cust `[17-25]` → acct `[26-36]`. The five rows resolve as:

| CARD-NUM | XREF-CUST-ID | XREF-ACCT-ID | Customer / CARD name |
|---|---|---|---|
| `0500024453765740` | `000000050` | `00000000050` | Aniya Von |
| `0683586198171516` | `000000027` | `00000000027` | Ward Jones |
| `0923877193247330` | `000000002` | `00000000002` | Enrico Rosenbaum |
| `0927987108636232` | `000000020` | `00000000020` | Carter Veum |
| `0982496213629795` | `000000012` | `00000000012` | Maci Robel |

The ACCOUNT and CUSTOMER subsets are **exactly** ids {2, 12, 20, 27, 50}; every XREF
`cust-id` exists in `custdata.txt` and every XREF/CARD `acct-id` exists in `acctdata.txt`
(verified). Bonus (not required, but it keeps the extracts auditable): each CARD embossed
name equals the CUSTOMER **first + last** name for the linked account.

## 4. Expected outcome

Each program reads its indexed file sequentially to EOF, `DISPLAY`s a `START OF EXECUTION …`
banner, one block per record, then an `END OF EXECUTION …` banner, and `GOBACK`s with
**`RETURN-CODE = 0`**. The downstream golden agent captures the exact printed text into the
(planned) mirror `tests/golden/provisioning/happy_path/`; the counts and RC below are the
authoritative intent this fixture guarantees:

- `CBACT01C` → **5** account blocks (labeled fields per record plus a dashed separator
  line). It also writes the derived `OUTFILE`/`ARRYFILE`/`VBRC` files and calls the
  `COBDATFT` date routine — that extra behavior is the integration harness's concern
  (`tests/integration/test_provisioning.py`), not this fixture's.
- `CBACT02C` → **5** card records displayed.
- `CBACT03C` → **5** cross-reference records displayed.
- `CBCUS01C` → **5** customer records displayed.
- **All `RETURN-CODE = 0`; record counts 5 / 5 / 5 / 5.**

## 5. Verified field extracts

A few extracts per master (columns per the copybooks) so the field-extract assertions have
known targets:

**ACCOUNT (`CVACT01Y`).** For every record: `ACCT-ACTIVE-STATUS` (col 12) = `Y`;
`ACCT-CURR-CYC-CREDIT` (79–90) = `ACCT-CURR-CYC-DEBIT` (91–102) = `00000000000{`;
`ACCT-ADDR-ZIP` (103–112) = `A000000000`; `ACCT-GROUP-ID` (113–122) = blank (10 spaces).
- acct `00000000002`: `ACCT-CURR-BAL` (13–24) = `00000001580{`, `ACCT-CREDIT-LIMIT` (25–36) = `00000061300{`.
- acct `00000000050`: `ACCT-CURR-BAL` = `00000004920{`, `ACCT-CREDIT-LIMIT` = `00000061690{`.

> Note the money fields carry the zoned-decimal **sign overpunch** in the last byte
> (`{` = `+0`; see parent README §3.4), not a `+`/`-` or literal decimal point. Note also
> the copybook's **contractual field-name misspelling** `ACCT-EXPIRAION-DATE` — reproduced
> verbatim because the program compiles against it.

**CARD (`CVACT02Y`).** card `0923877193247330` → `CARD-ACCT-ID` (17–27) = `00000000002`,
`CARD-EMBOSSED-NAME` (31–80) = `Enrico Rosenbaum`, `CARD-ACTIVE-STATUS` (91) = `Y`.

**CUSTOMER (`CVCUS01Y`).** cust `000000002` → `CUST-FIRST-NAME` (10–34) = `Enrico`,
`CUST-MIDDLE-NAME` (35–59) = `April`, `CUST-LAST-NAME` (60–84) = `Rosenbaum`.

## 6. Derivation commands

The seeds under `app/data/ASCII/` are **read only, never edited**. These four commands
reproduce the fixtures in this folder byte-for-byte (input paths shown relative to repo
root; outputs land in this folder):

```bash
grep -E '^(00000000002|00000000012|00000000020|00000000027|00000000050)' app/data/ASCII/acctdata.txt > acctdata.txt
head -5 app/data/ASCII/carddata.txt > carddata.txt
head -5 app/data/ASCII/cardxref.txt | awk '{ printf "%-50s\n", $0 }' > cardxref.txt
grep -E '^(000000002|000000012|000000020|000000027|000000050)' app/data/ASCII/custdata.txt > custdata.txt
```

## 7. XREF 36 → 50 padding — WHY

The seed `cardxref` rows are **36 bytes**: they omit the copybook's trailing
`FILLER X(14)`. `CVACT03Y` defines the record as **50 bytes** and `CBACT03C`'s FD reads a
50-byte record, so each row is right-padded with 14 spaces to 50 bytes — making the fixture
byte-consistent with what the program actually reads and displays.

- **Assumption:** the omitted `FILLER` is spaces (the copybook FILLER carries no data).
- **Alternative considered:** leave the 36-byte rows and rely on
  `tests/helpers/vsam_loader.py` auto-padding short lines. That is a tolerated fallback, but
  a canonical 50-byte fixture is preferred here so the on-disk record equals the loaded
  record (the 50-byte form is canonical per `tests/fixtures/README.md`).

## 8. Load parameters

For `tests/helpers/load_indexed.sh` / `vsam_loader.py` and the golden/integration agents.
All `key_offset` = 0 (the key is the leading field), and every file is pre-sorted by its own
RECORD KEY for a clean sequential indexed load:

| File | reclen | key_length | key_offset | sorted by |
|---|---:|---:|---:|---|
| `acctdata.txt` | 300 | 11 | 0 | `ACCT-ID` asc (2, 12, 20, 27, 50) |
| `carddata.txt` | 150 | 16 | 0 | `CARD-NUM` asc |
| `cardxref.txt` | 50 | 16 | 0 | `XREF-CARD-NUM` asc |
| `custdata.txt` | 500 | 9 | 0 | `CUST-ID` asc (2, 12, 20, 27, 50) |

## 9. Determinism

These masters carry fixed content only — no processing timestamps and no varying fields —
so every load → run → compare is byte-identical across reruns.

## 10. Explainability note

This README is the mandatory AAP §0.10.1 Explainability artifact for the static `.txt`
fixtures in this folder (fixtures cannot carry docstrings, so the WHY lives here).

# `disclosure_group/default_fallback` -- the DEFAULT group reached on a missing key

## 1. Scenario intent

The fallback path, and a fixture built to discriminate it rather than merely to
trigger it. The dataset is populated and holds **two** of the seed's three
disclosure-group classifiers: `A000000000`, whose rows make the direct lookup
succeed, and `DEFAULT`, which is what a miss falls back to. The third classifier
`ZEROAPR` is deliberately **absent**, so an account carrying that group composes a
key with no row and the program must retry against `DEFAULT`.

Assumptions: keeping the `A000000000` rows is what makes this one file test both
outcomes. A file holding `DEFAULT` alone would make every lookup miss, so a
consumer could not tell a working fallback from a fallback that had swallowed a
direct hit.

---

## 2. The exact business rule it exercises

The `CBACT04C` DEFAULT-group fallback on **VSAM status 23** -- the
house document's own worked example of an exactly-stated business rule. The key is
the account's group classifier plus the transaction type plus the category. A key
not found returns status 23, and the program then substitutes the classifier
`DEFAULT` and reads again. The rate that reaches the accrual is the `DEFAULT`
group's rate -- not zero, and not the absent group's.

---

## 3. Expected outcome

Two outcomes from one file, and the difference between them is material rather
than nominal:

| Account's group | Key composed | Read | Rate used |
|---|---|---|---|
| `A000000000` | `A000000000` + `01` + `0001` | hits directly | **15.00** |
| `ZEROAPR` | `ZEROAPR` + `01` + `0001` | misses, status 23, retries on `DEFAULT` | **15.00** |

The second row is the assertion. In the **seed**, `ZEROAPR` carries a rate of
`0.00` on all seventeen of its rows -- it is a zero-interest product -- so an
account in that group accrues at **15.00** through the fallback where its own rows
would have given **0.00**. That is why the fallback is worth a scenario: the value
it produces is not a rounding difference and not zero, and a consumer asserting a
zero rate, or an error, would be asserting one of the two behaviours this fixture
exists to rule out.

---

## 4. Fixture bytes and governance

### 4.1 Per-file record width, record count and line ending

| File | Record width | Records | Line ending |
|---|---|---|---|
| `discgrp.txt` | 50 bytes | 34 | one trailing LF |

Every file in this directory uses **LF** line endings and carries no carriage return; a CRLF file would add a byte to every record and shift every offset after the first.

### 4.1.1 Derivation, per file

- **`discgrp.txt`** -- All thirty-four rows are verbatim ASCII seed rows --
**zero rows are authored**, and no padding normalisation was needed because the seed
rows are already 50 characters. Thirty-four is a **deliberate subset**: the seed
holds 51 rows, being exactly seventeen for each of `A000000000`, `DEFAULT` and
`ZEROAPR`, and this file keeps the first two groups whole and omits all seventeen
`ZEROAPR` rows.

Refactoring Rationale: the omission is the entire mechanism, so it is recorded as a
removal rather than described as a selection. Dropping `DEFAULT` instead would turn
the fallback into a second miss; dropping `A000000000` would leave no direct hit to
contrast against; and keeping all 51 rows would leave nothing missing at all and the
fallback would never be reached. Removing exactly one group, whole, is the only
subset that produces the miss while keeping both outcomes observable.

---

## 5. Failure modes these bytes can produce

Assumptions: the failure modes below are the ones a consumer of *these* bytes can hit, which is the Rule 1 exceptions element applied to a fixture rather than to a function.

- A record read at the wrong declared width mis-aligns every field after the first, and the symptom is a plausible-looking wrong value rather than an exception, which is why section 4.1 states the width per file.
- A file rewritten by an editor that appends a final newline to an intentionally zero-byte fixture turns it into a 1-byte file holding one zero-length record, which fails fixed-width parsing instead of exercising the empty path.
- A file rewritten with CRLF endings shifts every offset and every record boundary.

---

## 6. Consumer

The consumer is `services/reference-service/src/test/java/com/carddemo/reference/fixtures/ReferenceFixtureContractTest.java`, which loads every file in this tree from the classpath and asserts its width, its record count, its line ending and the field values named above. **The bytes are the contract**: a consumer's expected value is whatever these bytes decode to, and a disagreement is resolved by reading the bytes rather than by editing them.

---

## 7. Why these bytes and not others

Assumptions: the choices below are properties of the FIXTURE rather than of the
rule it exercises, so they are recorded here rather than in section 2. Each one is a
decision a later author could reverse without any test failing, which is why the
reason is written down beside it.

- The scenario asserts the fallback fired while BOTH groups are present, so absence is unavailable as evidence and the pair must do the work instead. Sixteen of the seventeen seeded pairs carry byte-identical rates under A000000000 and DEFAULT; pair 07|0001 is the only one that differs, at 00150{ against 00000{. ../../README.md section 6.3 measures the whole distribution. On any other pair the assertion passes identically whether the fallback fired or not, and would keep passing if the fallback were deleted.
- ZEROAPR is deliberately NOT in this file. It is the seed's zero-rate control group, and a third group whose rate coincides with DEFAULT's on the discriminating pair would reintroduce exactly the ambiguity the pair was chosen to remove.
- Thirty-four rows, not fifty-one: seventeen pairs x two groups. The file is a subset of the seed by group, never by pair, so every pair a lookup might use exists under both groups.

---

## 8. Hazards and choices the second derivation recorded

Assumptions: these bytes were derived TWICE, independently, and both records are kept
because each names hazards the other does not. The section above states why the bytes
are what they are; the paragraphs below state what a later edit of them would break.
Their labels are given unemphasised, as
[`docs/CODE_DOCUMENTATION_STANDARD.md`](../../../../../../../../docs/CODE_DOCUMENTATION_STANDARD.md)
requires -- the second derivation wrote them in bold, which that document lists among the
four ways the label has actually been written wrongly.

- Assumptions: the tables above list the fields **this scenario gives a value to**, each with its offset, its `PICTURE` and the copybook or program line that declares it. The complete record layout, including any field this scenario does not vary and the fill regime for each, is tabled once in the tree charter section 3.4 and is cited rather than restated in full. Copying a whole layout table into each scenario directory would create as many places for the geometry to drift as there are scenarios, which the charter forbids at its section 5.8; citing a line per field costs nothing and points a reader at the normative source rather than at a copy of it.
- Alternatives Considered: exercising any other pair. Rejected because on the other sixteen the two groups return byte-identical rates, so an assertion that the fallback fired would pass identically whether it fired or not - and would keep passing if the fallback were removed altogether. Pair `07|0001` is the only discriminator the seed offers.
- Assumptions: the `DEFAULT` rate for `07|0001` is **0.00**, and four in-repository artefacts agree on that reading - the ASCII seed `app/data/ASCII/discgrp.txt`, the reference-service Flyway reference seed, this fixture, and the immutable parity oracle under `tests/`. The EBCDIC twin of the seed reads 15.00 at that row; the divergence is recorded in `data-migration/README.md`, and the ASCII reading is the authority because it is the one the parity oracle asserts against.
- Trade-offs: thirty-four rows where seventeen would fit in half the bytes. Both groups are carried because the whole point is that both are PRESENT; a `DEFAULT`-only file is the house oracle's design and proves a different thing.
